-- ===================================================================
--  迁移脚本：移除飞书同步功能对应的字段
--  适用场景：数据库已经执行过旧版 init.sql，需要清掉飞书相关的列与索引。
--  全新部署不需要执行本脚本，init.sql 已不含这些字段。
-- ===================================================================

-- 1) 可选：清理历史飞书来源的数据（先删分块，再删文档）
-- DELETE c FROM document_chunks c
--   JOIN documents d ON c.document_id = d.id
--  WHERE d.source = 'FEISHU';
-- DELETE FROM documents WHERE source = 'FEISHU';

-- 2) 删除飞书相关索引与字段
ALTER TABLE documents
    DROP INDEX uk_feishu_node,
    DROP COLUMN feishu_node_token,
    DROP COLUMN feishu_obj_type,
    DROP COLUMN feishu_update_time;
