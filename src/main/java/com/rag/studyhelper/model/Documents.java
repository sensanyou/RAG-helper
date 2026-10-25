package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档信息 表
 */
@Data
@TableName("documents")
public class Documents {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID，0=系统文档（对所有用户可见）。数据隔离的核心字段 */
    private Long ownerId;

    private String documentName;

    private String documentType;

    private String source;

    private String contentHash;

    private Long fileSize;

    private Integer chunkCount;

    private String creator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
