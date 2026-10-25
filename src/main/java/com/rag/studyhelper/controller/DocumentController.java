package com.rag.studyhelper.controller;

import com.rag.studyhelper.config.RateLimit;
import com.rag.studyhelper.context.UserContext;
import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.IngestTask;
import com.rag.studyhelper.service.AsyncIngestService;
import com.rag.studyhelper.service.DocumentIngestionService;
import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文档管理控制器（V2：所有接口强制归属校验）。
 * <p>
 * <b>userId 从哪来？</b>
 * 统一从 {@link UserContext}（ThreadLocal）取，
 * 它由认证拦截器从 token 解出——<b>绝不接受前端传 user_id 参数</b>。
 * 前端传什么都可以伪造，这是数据隔离的第一原则。
 * <p>
 * <b>同步上传 vs 异步上传，怎么选？</b>
 * <ul>
 *   <li>{@code POST /upload}：同步，适合小文件，能立刻拿到结果；</li>
 *   <li>{@code POST /upload-async}：异步，立即返回任务 ID，<b>推荐</b>，
 *       不占用 Tomcat 线程。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private AsyncIngestService asyncIngestService;

    /**
     * 上传文件（同步）。会阻塞直到解析 + 向量化 + 双写完成。
     */
    @RateLimit(value = "upload", maxCapacity = 10, supplementTimeOfSeconds = 60)
    @PostMapping("/upload")
    public Results<DocumentInfo> upload(@RequestParam("file") MultipartFile file) {
        long ownerId = UserContext.requireUserId();
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null) {
            throw BusinessException.badRequest("文件为空");
        }

        try {
            DocumentInfo result = documentIngestionService.ingestDocument(ownerId, originalName, file.getInputStream());
            return Results.success(result);
        } catch (IOException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "400",
                    "上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传文件（异步）。立即返回 taskId，前端轮询任务状态。
     */
    @RateLimit(value = "upload-async", maxCapacity = 60, supplementTimeOfSeconds = 60)
    @PostMapping("/upload-async")
    public Results<IngestTask> uploadAsync(@RequestParam("file") MultipartFile file) {
        long ownerId = UserContext.requireUserId();
        IngestTask task = asyncIngestService.submit(ownerId, file);
        return Results.success("任务已提交，请轮询任务状态", task);
    }

    /**
     * 查询异步入库任务状态（只能查自己提交的任务）。
     */
    @GetMapping("/task/{taskId}")
    public Results<IngestTask> getTaskStatus(@PathVariable String taskId) {
        long ownerId = UserContext.requireUserId();
        IngestTask task = asyncIngestService.getTask(ownerId, taskId);
        if (task == null) {
            // 故意不区分"不存在"和"不是你的"——防止探测他人 taskId
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND, "404",
                    "任务不存在或已过期: " + taskId, null);
        }
        return Results.success(task);
    }

    /**
     * 我的任务列表（最近 20 条）。
     */
    @GetMapping("/tasks")
    public Results<List<IngestTask>> listMyTasks() {
        long ownerId = UserContext.requireUserId();
        return Results.success(asyncIngestService.listMyTasks(ownerId, 20));
    }

    /**
     * 失败任务重试。
     */
    @PostMapping("/task/{taskId}/retry")
    public Results<IngestTask> retryTask(@PathVariable String taskId) {
        long ownerId = UserContext.requireUserId();
        return Results.success("已复位为待处理", asyncIngestService.retry(ownerId, taskId));
    }

    /**
     * 我的文档列表（缓存 key 按用户隔离）。
     */
    @GetMapping
    public Results<List<DocumentInfo>> listDocuments() {
        long ownerId = UserContext.requireUserId();
        return Results.success(documentIngestionService.getIngestedDocumentsSync(ownerId));
    }

    /**
     * 扫描 data/docs 目录并导入为当前用户的文档。
     */
    @PostMapping("/scan")
    public Results<List<DocumentInfo>> scanDocuments() {
        long ownerId = UserContext.requireUserId();
        return Results.success(documentIngestionService.scanAndIngest(ownerId));
    }

    /**
     * 删除文档（校验归属：只能删自己的文档）。
     */
    @DeleteMapping("/{id}")
    public Results<Void> deleteDocument(@PathVariable Long id) {
        long ownerId = UserContext.requireUserId();
        documentIngestionService.deleteDocument(ownerId, id);
        return Results.success("删除成功");
    }
}
