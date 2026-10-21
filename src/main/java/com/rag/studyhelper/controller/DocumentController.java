package com.rag.studyhelper.controller;

import com.rag.studyhelper.config.RateLimit;
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
 * 文档管理控制器。
 * <p>
 * <b>同步上传 vs 异步上传，怎么选？</b>
 * <ul>
 *   <li>{@code POST /upload}：同步。请求线程一直等到入库完成才返回，
 *       适合小文件（几十 KB 的笔记），前端能拿到立刻的结果。</li>
 *   <li>{@code POST /upload-async}：异步。立即返回任务 ID，后台线程处理，
 *       适合大文件（PDF/PPT，几十秒）。<b>这是推荐方式</b>，
 *       因为它不会长时间占用 Tomcat 线程。</li>
 * </ul>
 * 两个接口并存是刻意的：让学习者能直接对比同一份文件走两条路的耗时差异。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private AsyncIngestService asyncIngestService;

    /**
     * 上传文件（同步）。
     * <p>
     * 注意这个方法<b>会阻塞</b>直到解析 + 向量化 + 双写全部完成。
     * 大文件请改用 {@link #uploadAsync(MultipartFile)}。
     */
    @RateLimit(value = "upload", maxCapacity = 10, supplementTimeOfSeconds = 60)
    @PostMapping("/upload")
    public Results<DocumentInfo> upload(@RequestParam("file") MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null) {
            throw BusinessException.badRequest("文件为空");
        }

        try {
            DocumentInfo result = documentIngestionService.ingestDocument(originalName, file.getInputStream());
            return Results.success(result);
        } catch (IOException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "400",
                    "上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传文件（异步）。
     * <p>
     * 立即返回 {@code taskId}（毫秒级），真正的解析与向量化在 {@code ingestExecutor}
     * 线程池中执行。前端拿到 taskId 后轮询 {@link #getTaskStatus(String)}。
     * <p>
     * <b>限流参数比同步接口宽松</b>（60 次/分钟 vs 10 次/分钟），
     * 因为异步接口本身响应极快，不会占用资源；
     * 真正消耗资源的入库操作已经由线程池的队列容量（200）保护住了。
     * 这是"限流应该卡在资源消耗点，而不是卡在接口调用点"的体现。
     */
    @RateLimit(value = "upload-async", maxCapacity = 60, supplementTimeOfSeconds = 60)
    @PostMapping("/upload-async")
    public Results<IngestTask> uploadAsync(@RequestParam("file") MultipartFile file) {
        IngestTask task = asyncIngestService.submit(file);
        return Results.success("任务已提交，请轮询任务状态", task);
    }

    /**
     * 查询异步入库任务状态。
     * <p>
     * 返回的 {@code status} 有四种取值：
     * <ul>
     *   <li>{@code PENDING}：已排队，等待线程池调度</li>
     *   <li>{@code RUNNING}：正在解析/向量化</li>
     *   <li>{@code SUCCESS}：完成，{@code documentId} 和 {@code chunks} 有值</li>
     *   <li>{@code FAILED}：失败，{@code errorMessage} 说明原因</li>
     * </ul>
     */
    @GetMapping("/task/{taskId}")
    public Results<IngestTask> getTaskStatus(@PathVariable String taskId) {
        IngestTask task = asyncIngestService.getTask(taskId);
        if (task == null) {
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND, "404",
                    "任务不存在或已过期: " + taskId, null);
        }
        return Results.success(task);
    }

    /**
     * 文档列表。
     * <p>
     * 走 {@code sync = true} 版本：并发场景下只放一个线程查库，
     * 其余线程等结果，防止缓存过期瞬间把数据库打穿（缓存击穿防护）。
     */
    @GetMapping
    public Results<List<DocumentInfo>> listDocuments() {
        return Results.success(documentIngestionService.getIngestedDocumentsSync());
    }

    // 扫描并导入文件
    @PostMapping("/scan")
    public Results<List<DocumentInfo>> scanDocuments() {
        return Results.success(documentIngestionService.scanAndIngest());
    }

    @DeleteMapping("/{id}")
    public Results<Void> deleteDocument(@PathVariable Long id) {
        documentIngestionService.deleteDocument(id);
        return Results.success("删除成功");
    }
}
