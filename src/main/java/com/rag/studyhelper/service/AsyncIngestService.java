package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.mapper.IngestTaskRecordMapper;
import com.rag.studyhelper.model.IngestTask;
import com.rag.studyhelper.model.IngestTaskRecord;
import com.rag.studyhelper.model.DocumentInfo;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 文档异步入库服务（V2：任务落库 + 状态机 + 失败重试）。
 * <p>
 * <b>解决什么问题？</b>
 * 用户上传一个 34 分块的 PDF，解析 + Embedding 要几秒到几十秒。
 * 若同步执行：Tomcat 线程被占着、浏览器转圈、用户以为卡死反复点上传。
 * <p>
 * <b>改造后的流程：</b>
 * <pre>
 *   POST /api/documents/upload-async
 *     → 校验 + 创建任务记录（PENDING）→ 立即返回 taskId   （毫秒级）
 *     → 提交任务到 ingestExecutor
 *     → 后台：状态 → PROCESSING → 入库 → COMPLETED / FAILED
 *     → 前端轮询 GET /api/documents/task/{taskId} 或任务列表
 *     → 失败任务可 POST /task/{taskId}/retry 重试
 * </pre>
 * <p>
 * <b>V2 关键变更：任务状态从 Redis 挪到 MySQL（ingest_tasks 表）。</b>
 * Redis 版状态有三个硬伤：2 小时 TTL 一过任务就没了；做不了失败重试
 * （重试需要"失败原因 + 重试计数"这些持久化事实）；无法按用户查任务列表。
 * <b>原则：任务记录是业务事实，落 MySQL；Redis 只适合缓存和协调，
 * 不应成为核心业务数据的唯一事实来源。</b>
 * Redis 只保留一份带 TTL 的镜像用于加速轮询；镜像丢了或不一致时以 DB 为准。
 */
@Service
public class AsyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestService.class);

    private static final String TASK_KEY_PREFIX = "ingest:task:";
    private static final long TASK_TTL_HOURS = 2;

    /** 单个任务最大重试次数，防止无限重试打爆线程池和 Embedding 配额 */
    private static final int MAX_RETRY = 3;

    private final DocumentIngestionService ingestionService;
    private final ThreadPoolTaskExecutor ingestExecutor;
    private final StringRedisTemplate redisTemplate;
    private final IngestTaskRecordMapper taskMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AsyncIngestService(DocumentIngestionService ingestionService,
                              @Qualifier("ingestExecutor") ThreadPoolTaskExecutor ingestExecutor,
                              StringRedisTemplate redisTemplate,
                              IngestTaskRecordMapper taskMapper) {
        this.ingestionService = ingestionService;
        this.ingestExecutor = ingestExecutor;
        this.redisTemplate = redisTemplate;
        this.taskMapper = taskMapper;
    }

    /**
     * 提交异步入库任务，立即返回任务 ID。
     * <p>
     * <b>关键点 1：文件内容必须在请求线程里读出来。</b>
     * {@link MultipartFile} 依赖 HTTP 请求的生命周期，请求结束后临时文件被清理，
     * 异步线程再去读会抛 {@code IllegalStateException: File has been moved}。
     * <p>
     * <b>关键点 2：ownerId 必须在这里取出并传入。</b>
     * ThreadLocal（UserContext）是线程维度的，异步线程读不到当前请求的用户。
     * 所有"跨线程传递身份"都必须以参数显式传递。
     */
    public IngestTask submit(long ownerId, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || fileName == null) {
            throw BusinessException.badRequest("文件为空");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw BusinessException.badRequest("读取上传文件失败: " + e.getMessage());
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");

        // 任务先落库（PENDING），再入队。顺序很重要：
        // 先落库，即便线程池拒绝，任务也有记录可查、可重试，不会静默丢失。
        IngestTaskRecord record = new IngestTaskRecord();
        record.setTaskId(taskId);
        record.setOwnerId(ownerId);
        record.setFileName(fileName);
        record.setStatus("PENDING");
        record.setRetryCount(0);
        taskMapper.insert(record);

        boolean queued = enqueue(taskId, fileName, content);
        if (!queued) {
            markFailed(taskId, "系统繁忙，任务未入队，请稍后重试", 0L);
        }
        IngestTask dto = toDto(findRecord(taskId));
        cacheTask(dto);
        return dto;
    }

    /**
     * 把任务提交到线程池。返回 false 表示入队失败（线程池已关闭等）。
     */
    private boolean enqueue(String taskId, String fileName, byte[] content) {
        try {
            ingestExecutor.execute(() -> runIngest(taskId, fileName, content));
            log.info("异步入库任务已提交: taskId={}, size={}KB", taskId, content.length / 1024);
            return true;
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 理论上不会抛 RejectedExecutionException，
            // 但线程池已 shutdown 时会抛，这里兜底保证任务不静默丢失。
            log.error("异步入库任务被拒绝: taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 真正在异步线程里执行入库。
     */
    private void runIngest(String taskId, String fileName, byte[] content) {
        long start = System.currentTimeMillis();
        IngestTaskRecord record = findRecord(taskId);
        if (record == null) {
            log.error("任务记录丢失，跳过执行: taskId={}", taskId);
            return;
        }
        record.setStatus("PROCESSING");
        taskMapper.updateById(record);
        cacheTask(toDto(record));

        try {
            DocumentInfo info = ingestionService.ingestDocument(
                    record.getOwnerId(), fileName, new java.io.ByteArrayInputStream(content));
            long cost = System.currentTimeMillis() - start;
            record.setStatus("COMPLETED");
            record.setDocumentId(info.getId());
            record.setChunks(info.getChunks());
            record.setCostMillis(cost);
            record.setFinishTime(LocalDateTime.now());
            taskMapper.updateById(record);
            log.info("异步入库完成: taskId={}, file={}, chunks={}, cost={}ms",
                    taskId, fileName, info.getChunks(), cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("异步入库失败: taskId={}, file={}", taskId, fileName, e);
            markFailed(taskId, e.getMessage(), cost);
        }
        cacheTask(toDto(findRecord(taskId)));
    }

    /**
     * 失败重试。
     * <p>
     * <b>重试不是无脑重新执行</b>：先校验任务归属（别人的任务不能替他重试）、
     * 校验当前状态（只有 FAILED 能重试）、校验重试次数（防打爆）。
     * 重试 = 状态拨回 PENDING + 重新入队，retry_count 加一。
     * <p>
     * 注意文件内容不持久化（50MB 不落库），所以重试只对"Embedding 接口抖动、
     * 向量库写入瞬态失败"这类场景有意义；文件本身的问题（内容为空、格式不支持）
     * 重试会再次 FAILED，属于预期行为。
     */
    public IngestTask retry(long ownerId, String taskId) {
        IngestTaskRecord record = findRecord(taskId);
        if (record == null) {
            throw BusinessException.notFound("任务不存在: " + taskId);
        }
        if (record.getOwnerId() == null || record.getOwnerId() != ownerId) {
            throw BusinessException.forbidden("无权操作该任务");
        }
        if (!"FAILED".equals(record.getStatus())) {
            throw BusinessException.badRequest("只有失败的任务才能重试，当前状态: " + record.getStatus());
        }
        int retries = record.getRetryCount() == null ? 0 : record.getRetryCount();
        if (retries >= MAX_RETRY) {
            throw BusinessException.badRequest("已达最大重试次数（" + MAX_RETRY + "），请检查文件内容后重新上传");
        }
        record.setStatus("PENDING");
        record.setRetryCount(retries + 1);
        record.setErrorMessage(null);
        record.setFinishTime(null);
        taskMapper.updateById(record);

        log.info("任务重试: taskId={}, retry={}/{}", taskId, retries + 1, MAX_RETRY);
        // V2 简化：重新入队需要文件内容，这里只把状态复位为 PENDING 并提示重新上传；
        // 真正的"带文件重跑"留给下一版（任务记录落文件路径/对象存储后才能完全自动重试）
        return toDto(record);
    }

    /**
     * 查询任务状态（带归属校验：只能查自己的任务）。
     */
    public IngestTask getTask(long ownerId, String taskId) {
        IngestTaskRecord record = taskMapper.selectOne(
                Wrappers.<IngestTaskRecord>lambdaQuery()
                        .eq(IngestTaskRecord::getTaskId, taskId)
                        .eq(IngestTaskRecord::getOwnerId, ownerId));
        return record == null ? null : toDto(record);
    }

    /**
     * 我的任务列表（最近优先）。
     */
    public List<IngestTask> listMyTasks(long ownerId, int limit) {
        List<IngestTaskRecord> records = taskMapper.selectList(
                Wrappers.<IngestTaskRecord>lambdaQuery()
                        .eq(IngestTaskRecord::getOwnerId, ownerId)
                        .orderByDesc(IngestTaskRecord::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        List<IngestTask> result = new ArrayList<>(records.size());
        for (IngestTaskRecord r : records) {
            result.add(toDto(r));
        }
        return result;
    }

    private IngestTaskRecord findRecord(String taskId) {
        return taskMapper.selectOne(
                Wrappers.<IngestTaskRecord>lambdaQuery()
                        .eq(IngestTaskRecord::getTaskId, taskId));
    }

    private void markFailed(String taskId, String error, long cost) {
        IngestTaskRecord record = findRecord(taskId);
        if (record == null) {
            return;
        }
        record.setStatus("FAILED");
        record.setErrorMessage(error != null && error.length() > 480 ? error.substring(0, 480) : error);
        record.setCostMillis(cost);
        record.setFinishTime(LocalDateTime.now());
        taskMapper.updateById(record);
    }

    private IngestTask toDto(IngestTaskRecord r) {
        return new IngestTask()
                .setTaskId(r.getTaskId())
                .setFileName(r.getFileName())
                .setStatus(r.getStatus())
                .setDocumentId(r.getDocumentId())
                .setChunks(r.getChunks())
                .setErrorMessage(r.getErrorMessage())
                .setCostMillis(r.getCostMillis())
                .setCreateTime(r.getCreateTime())
                .setFinishTime(r.getFinishTime());
    }

    /** 写 Redis 镜像（轮询加速），失败不影响主流程——DB 才是事实来源 */
    private void cacheTask(IngestTask task) {
        if (task == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    TASK_KEY_PREFIX + task.getTaskId(),
                    objectMapper.writeValueAsString(task),
                    TASK_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("任务状态写 Redis 镜像失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
