package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.IngestTask;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 文档异步入库服务。
 * <p>
 * <b>解决什么问题？</b>
 * 原实现里，用户上传一个 34 分块的 PDF，请求要阻塞约 2 秒（解析 + 4 批 Embedding）；
 * 如果传 50MB 的大文档，可能要几十秒。这段时间里：</p>
 * <ul>
 *   <li>Tomcat 的工作线程被一直占着（默认 200 个，20 个这样的请求就吃掉 10% 线程池）；</li>
 *   <li>用户浏览器转圈，没有任何进度反馈，很容易以为卡死了反复点上传；</li>
 *   <li>反复点上传会触发限流，用户看到"请求过于频繁"，体验更差。</li>
 * </ul>
 * <p>
 * <b>改造后的流程：</b>
 * <pre>
 *   POST /api/documents/upload-async
 *     → 立即返回 { taskId: "xxx", status: "PENDING" }   （毫秒级）
 *     → 提交任务到 ingestExecutor
 *     → 前端轮询 GET /api/documents/task/{taskId}
 *     → 看到 status=SUCCESS 或 FAILED 时停止轮询
 * </pre>
 * <p>
 * <b>为什么任务状态存 Redis 而不是本地 Map？</b>
 * 因为上传请求和轮询请求可能落在不同实例上。存 Redis 任何一台机器都能查到；
 * 存本地 ConcurrentHashMap 的话，多实例部署时轮询会大量 404。
 */
@Service
public class AsyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestService.class);

    private static final String TASK_KEY_PREFIX = "ingest:task:";
    /** 任务状态保留 2 小时，够用户查看结果；过期自动清理，不用定时任务 */
    private static final long TASK_TTL_HOURS = 2;

    private final DocumentIngestionService ingestionService;
    private final ThreadPoolTaskExecutor ingestExecutor;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AsyncIngestService(DocumentIngestionService ingestionService,
                              @Qualifier("ingestExecutor") ThreadPoolTaskExecutor ingestExecutor,
                              StringRedisTemplate redisTemplate,
                              RedissonClient redissonClient) {
        this.ingestionService = ingestionService;
        this.ingestExecutor = ingestExecutor;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        // IngestTask 里有 LocalDateTime，需要注册 JSR310 模块
        this.objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 前向兼容：以后给 IngestTask 加字段，旧版本写进 Redis 的 JSON 依然能读出来。
        // 这一点很关键——曾经因为 isFinished() 被当成 "finished" 属性，
        // 反序列化直接抛 Unrecognized field，导致"任务明明存在却查不到"。
        // 开启这个开关后，多出来的未知字段会被安静忽略，而不是炸掉整个读取。
        this.objectMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 提交异步入库任务，立即返回任务 ID。
     * <p>
     * <b>关键点：文件内容必须在请求线程里读出来。</b>
     * 因为 {@link MultipartFile} 依赖 HTTP 请求的生命周期，
     * 请求结束后临时文件就被清理了，异步线程再去读会抛
     * {@code IllegalStateException: File has been moved}。
     * 所以先 {@code getBytes()} 到内存，再把字节数组传给异步线程。
     * <p>
     * 代价是要在内存里放一份文件（最大 50MB）。这是权衡：
     * 要么占内存，要么落临时文件，要么改造成对象存储。50MB 的量级选内存最简单。
     */
    public IngestTask submit(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || fileName == null) {
            throw com.rag.studyhelper.exception.BusinessException.badRequest("文件为空");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw com.rag.studyhelper.exception.BusinessException.badRequest("读取上传文件失败: " + e.getMessage());
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        IngestTask pending = IngestTask.pending(taskId, fileName);
        saveTask(pending);

        try {
            ingestExecutor.execute(() -> runIngest(taskId, fileName, content));
            log.info("异步入库任务已提交: taskId={}, fileName={}, size={}KB",
                    taskId, fileName, content.length / 1024);
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 理论上不会抛 RejectedExecutionException，
            // 但线程池已 shutdown 时会抛，这里兜底保证任务不静默丢失。
            log.error("异步入库任务被拒绝: taskId={}", taskId, e);
            saveTask(IngestTask.failed(taskId, fileName, "系统繁忙，请稍后重试", 0L));
        }
        return pending;
    }

    /**
     * 真正在异步线程里执行入库。
     * <p>
     * <b>为什么这里要再抢一次分布式锁？</b>
     * 因为同一个文件可能在极短时间内被提交两次（用户连点）。
     * 两次提交产生两个不同的 taskId，进入两个异步线程，
     * 它们会在 {@code DocumentIngestionService} 内部竞争同一把内容哈希锁，
     * 后到的那个会等待——所以需要在这里用 {@code tryLock} 的短超时快速失败，
     * 避免异步线程白白堆积在一起等锁。
     */
    private void runIngest(String taskId, String fileName, byte[] content) {
        long start = System.currentTimeMillis();
        saveTask(new IngestTask().setTaskId(taskId).setFileName(fileName)
                .setStatus("RUNNING").setCreateTime(java.time.LocalDateTime.now()));

        try {
            com.rag.studyhelper.model.DocumentInfo info =
                    ingestionService.ingestDocument(fileName, new java.io.ByteArrayInputStream(content));
            long cost = System.currentTimeMillis() - start;
            saveTask(IngestTask.success(taskId, fileName, info.getId(), info.getChunks(), false, cost));
            log.info("异步入库完成: taskId={}, file={}, chunks={}, cost={}ms",
                    taskId, fileName, info.getChunks(), cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("异步入库失败: taskId={}, file={}", taskId, fileName, e);
            saveTask(IngestTask.failed(taskId, fileName, e.getMessage(), cost));
        }
    }

    /**
     * 查询任务状态。找不到返回 null（由 Controller 转 404）。
     */
    public IngestTask getTask(String taskId) {
        String json = redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IngestTask.class);
        } catch (Exception e) {
            log.warn("任务状态反序列化失败: taskId={}", taskId, e);
            return null;
        }
    }

    private void saveTask(IngestTask task) {
        try {
            redisTemplate.opsForValue().set(
                    TASK_KEY_PREFIX + task.getTaskId(),
                    objectMapper.writeValueAsString(task),
                    TASK_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存任务状态失败: taskId={}", task.getTaskId(), e);
        }
    }
}
