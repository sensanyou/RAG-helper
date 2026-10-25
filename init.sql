-- ===================================================================
--  RAG Study Helper - 数据库初始化脚本
--  版本: V2.0 (多用户版)
--  变更:
--    V1.0 文档元数据 + 分块映射表
--    V2.0 新增用户表、任务表；documents 增加 owner_id（数据隔离）
--  注意: 字符集使用 utf8mb4 以支持中文和 emoji
-- ===================================================================

-- -------------------------------------------------------------------
-- 用户表
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL COMMENT '登录名（唯一）',
    password_hash   VARCHAR(255) NOT NULL COMMENT 'PBKDF2 哈希，格式: pbkdf2:迭代次数:saltHex:hashHex，禁止明文',
    display_name    VARCHAR(64)  DEFAULT NULL COMMENT '昵称（展示用）',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -------------------------------------------------------------------
-- 文档元数据表
-- owner_id = 0 表示系统文档（启动扫描 data/docs 导入），对所有用户可见
-- 去重唯一键是 (owner_id, content_hash)：同一个文件不同用户各自拥有一份。
-- 全局 hash 唯一在多用户下会导致"B 上传 A 已有的文件 → 拿到 A 的文档 id"，
-- 属于跨用户数据泄露，必须用户维度去重。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documents (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '所属用户 ID，0=系统',
    document_name       VARCHAR(255) NOT NULL COMMENT '文档显示名称',
    document_type       VARCHAR(20)  NOT NULL COMMENT '文件类型: pdf|txt|md|docx|xlsx|pptx|html',
    source              VARCHAR(20)  NOT NULL COMMENT '来源: UPLOAD | SCAN',
    content_hash        VARCHAR(64)  DEFAULT NULL COMMENT '内容SHA256，上传/扫描去重用',
    file_size           BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',
    chunk_count         INT          DEFAULT 0 COMMENT '向量化分块数',
    creator             VARCHAR(64)  DEFAULT NULL COMMENT '创建人用户名（冗余展示字段）',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_owner_hash (owner_id, content_hash),
    INDEX idx_owner_create (owner_id, create_time),
    INDEX idx_document_name (document_name),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档元数据表';

-- -------------------------------------------------------------------
-- 文档分块映射表
-- 分块通过 document_id 间接关联 owner（join 查询），不冗余 owner_id，
-- 避免两处维护归属关系。凡是要按用户过滤分块，一律先查该用户的文档 id 列表。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document_chunks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT       NOT NULL COMMENT '所属文档ID',
    vector_id       VARCHAR(64)  NOT NULL COMMENT '向量库中的chunk ID',
    chunk_index     INT          NOT NULL COMMENT '分块序号',
    chunk_text      TEXT         NOT NULL COMMENT '分块文本',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_document_id (document_id),
    INDEX idx_vector_id (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块映射表';

-- -------------------------------------------------------------------
-- 异步入库任务表
-- V1 的任务状态只存 Redis（2h TTL），做不了失败重试与服务重启后的状态恢复，
-- 也满足不了"按用户查任务列表"的需求。任务记录是业务事实，落 MySQL；
-- Redis 只作为查询加速（缓存最新状态），不是唯一事实来源。
-- 状态机: PENDING → PROCESSING → COMPLETED / FAILED（FAILED 可重试回 PENDING）
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingest_tasks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(32)  NOT NULL COMMENT '对外暴露的任务ID（UUID 去横线）',
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
