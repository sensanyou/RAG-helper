package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步入库任务表实体（持久化在 MySQL）。
 * <p>
 * <b>为什么任务从 Redis 挪到 MySQL？</b>
 * Redis 版任务状态有两个硬伤：
 * <ol>
 *   <li>2 小时 TTL 一过任务就没了，做不了"失败重试"和"重启后恢复"；</li>
 *   <li>Redis 里无法按 owner 高效查"我提交过的所有任务"。</li>
 * </ol>
 * 任务记录是业务事实，事实必须有唯一的持久化归宿（MySQL）；
 * Redis 只保留一份带 TTL 的镜像用来加速轮询查询——
 * <b>原则：Redis 适合缓存和协调，不应成为核心业务数据的唯一事实来源。</b>
 * <p>
 * 状态机：{@code PENDING → PROCESSING → COMPLETED / FAILED}，
 * FAILED 的任务可以重试（retry_count + 1，状态拨回 PENDING 重新入队）。
 */
@Data
@TableName("ingest_tasks")
public class IngestTaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外暴露的任务 ID（UUID 去横线） */
    private String taskId;

    /** 提交人用户 ID */
    private Long ownerId;

    private String fileName;

    /** PENDING / PROCESSING / COMPLETED / FAILED */
    private String status;

    private Long documentId;

    private Integer chunks;

    private String errorMessage;

    private Integer retryCount;

    private Long costMillis;

    private LocalDateTime createTime;

    private LocalDateTime finishTime;
}
