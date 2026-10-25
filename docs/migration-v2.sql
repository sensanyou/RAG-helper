-- ===================================================================
--  RAG Study Helper - 增量迁移脚本 V1 → V2 (多用户版)
--  适用：已经用 init.sql V1.0 建过库的环境。
--  全新部署不需要本脚本，直接跑 init.sql 即可。
--  幂等性：每条语句尽量可重复执行（MySQL 8.0 的 ADD COLUMN IF NOT EXISTS
--  不可用，重复执行报 Duplicate column 时忽略即可）。
-- ===================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL COMMENT '登录名（唯一）',
    password_hash   VARCHAR(255) NOT NULL COMMENT 'PBKDF2 哈希，格式: pbkdf2:迭代次数:saltHex:hashHex',
    display_name    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. documents 增加 owner_id（0=系统文档，所有人可见）
ALTER TABLE documents
    ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户 ID，0=系统' AFTER id;

-- 3. 唯一键从全局 hash 改为 (owner_id, content_hash)
--    全局唯一在多用户下是跨用户数据泄露：B 上传 A 已有的文件会拿到 A 的文档
ALTER TABLE documents DROP INDEX uk_content_hash;
ALTER TABLE documents
    ADD UNIQUE KEY uk_owner_hash (owner_id, content_hash),
    ADD INDEX idx_owner_create (owner_id, create_time);

-- creator 放宽为可空（系统文档没有创建人）
ALTER TABLE documents MODIFY COLUMN creator VARCHAR(64) DEFAULT NULL COMMENT '创建人用户名（冗余展示字段）';

-- 4. 异步入库任务表
CREATE TABLE IF NOT EXISTS ingest_tasks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(32)  NOT NULL COMMENT '对外暴露的任务ID',
    owner_id        BIGINT       NOT NULL COMMENT '提交人用户 ID',
    file_name       VARCHAR(255) NOT NULL COMMENT '文件名',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|PROCESSING|COMPLETED|FAILED',
    document_id     BIGINT       DEFAULT NULL COMMENT '成功后关联的文档 ID',
    chunks          INT          DEFAULT NULL COMMENT '成功后的分块数',
    error_message   VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    cost_millis     BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time     DATETIME     DEFAULT NULL COMMENT '结束时间',
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_owner_create (owner_id, create_time),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步入库任务表';
