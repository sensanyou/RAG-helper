package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体。
 * <p>
 * <b>为什么密码只有一个 hash 字段？</b>
 * 哈希串自包含全部校验信息（算法、迭代次数、盐、哈希值），
 * 格式 {@code pbkdf2:iterations:saltHex:hashHex}。
 * 这样以后升级迭代次数或换算法时，老用户登录一次就能自动重哈希升级，
 * 不需要额外的 salt 列和算法列。
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String displayName;

    /** 1=正常 0=禁用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
