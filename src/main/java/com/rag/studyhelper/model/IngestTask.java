package com.rag.studyhelper.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 异步入库任务状态。
 * <p>
 * 用于"上传立即返回任务ID，后台慢慢处理，前端轮询进度"的异步模式。
 * 状态流转：{@code PENDING → RUNNING → SUCCESS / FAILED}
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class IngestTask {

    /** 任务 ID（UUID） */
    private String taskId;

    /** 文件名 */
    private String fileName;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 成功后返回的文档 ID */
    private Long documentId;

    /** 分块数量 */
    private Integer chunks;

    /** 失败原因 */
    private String errorMessage;

    /** 是否命中去重（文件已存在，直接返回已有记录） */
    private boolean duplicated;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 耗时（毫秒），用于观察入库性能 */
    private Long costMillis;

    public static IngestTask pending(String taskId, String fileName) {
        return new IngestTask()
                .setTaskId(taskId)
                .setFileName(fileName)
                .setStatus("PENDING")
                .setCreateTime(LocalDateTime.now());
    }

    public static IngestTask success(String taskId, String fileName, Long documentId,
                                     Integer chunks, boolean duplicated, long costMillis) {
        return new IngestTask()
                .setTaskId(taskId)
                .setFileName(fileName)
                .setStatus("SUCCESS")
                .setDocumentId(documentId)
                .setChunks(chunks)
                .setDuplicated(duplicated)
                .setCostMillis(costMillis)
                .setCreateTime(LocalDateTime.now())
                .setFinishTime(LocalDateTime.now());
    }

    public static IngestTask failed(String taskId, String fileName, String error, long costMillis) {
        return new IngestTask()
                .setTaskId(taskId)
                .setFileName(fileName)
                .setStatus("FAILED")
                .setErrorMessage(error)
                .setCostMillis(costMillis)
                .setCreateTime(LocalDateTime.now())
                .setFinishTime(LocalDateTime.now());
    }

    /**
     * 任务是否已结束（派生属性，不存字段）。
     * <p>
     * <b>这个 {@code @JsonProperty(access = READ_ONLY)} 解决了一个很隐蔽的坑：</b>
     * Jackson 会把 {@code isFinished()} 当成名为 {@code finished} 的<b>可读写属性</b>，
     * 于是序列化时多写一个 {@code "finished": true} 字段；
     * 但反序列化时找不到对应的 setter（它没有 setFinished），直接抛
     * <pre>
     * Unrecognized field "finished" (class com.rag.studyhelper.model.IngestTask)
     * </pre>
     * 表现就是：任务状态能写进 Redis，但每次查询都反序列化失败、接口返回
     * 404"任务不存在或已过期"——看起来像"任务丢了"，实际是读不出来。
     * <p>
     * 之所以用 {@code READ_ONLY} 而不是 {@code @JsonIgnore}：
     * 前端仍然需要这个字段来判断"要不要停止轮询"，
     * {@code READ_ONLY} 的含义是"可以输出、反序列化时忽略"，正好符合需求。
     * <p>
     * <b>通用规则</b>：凡是"由其他字段推导出来的 getter"（isXxx / getXxx 计算属性），
     * 而该类又要参与 JSON 双向序列化，就必须显式声明读写语义，
     * 否则必在反序列化时出问题。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isFinished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
