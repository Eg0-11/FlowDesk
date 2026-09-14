-- FlowDesk 文档索引（向量化）生命周期（FD-0010，RAG 3/6）。
--
-- 目标数据库为 PostgreSQL；本地开发与自动化测试使用 H2 的 PostgreSQL 兼容模式，
-- 因此只用两者共有的标准 SQL。V1~V4 已发布，本脚本<b>不修改</b>它们。
--
-- 向量列本身是 pgvector 专属类型，因此放在 PostgreSQL 专用迁移
-- （db/postgresql-migration/V6）里；本脚本只扩展「文档这一侧」的生命周期字段，
-- 因此 H2 也能完整执行 —— 默认环境可以迁移、可以测试，只是不存在向量表
-- （真实索引功能在默认环境是关闭的，见 ADR 0007）。

-- ① 索引生命周期字段（全部可空：只有对应状态才允许非空）
ALTER TABLE knowledge_documents ADD COLUMN index_started_at TIMESTAMP(6) WITH TIME ZONE NULL;
ALTER TABLE knowledge_documents ADD COLUMN indexed_at TIMESTAMP(6) WITH TIME ZONE NULL;
ALTER TABLE knowledge_documents ADD COLUMN index_failed_at TIMESTAMP(6) WITH TIME ZONE NULL;
ALTER TABLE knowledge_documents ADD COLUMN index_failure_code VARCHAR(64) NULL;
ALTER TABLE knowledge_documents ADD COLUMN embedding_provider VARCHAR(32) NULL;
ALTER TABLE knowledge_documents ADD COLUMN embedding_model VARCHAR(128) NULL;
ALTER TABLE knowledge_documents ADD COLUMN embedding_dimensions INTEGER NULL;

-- ② 状态取值扩展：加上 INDEXING / INDEXED / INDEX_FAILED
ALTER TABLE knowledge_documents DROP CONSTRAINT ck_knowledge_documents_status;
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_status CHECK (
    status IN ('UPLOADED', 'PARSING', 'PARSED', 'PARSE_FAILED',
               'INDEXING', 'INDEXED', 'INDEX_FAILED'));

-- ③ 状态与解析字段必须严格对应。
--    「已索引」是「已解析」的下游状态，因此索引类状态仍然必须带 parsed_at；
--    解析失败字段绝不能与索引类状态同时出现。
ALTER TABLE knowledge_documents DROP CONSTRAINT ck_knowledge_documents_parse_state;
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_parse_state CHECK (
    (status = 'PARSED'
        AND parsed_at IS NOT NULL
        AND parse_failed_at IS NULL
        AND parse_failure_code IS NULL)
    OR (status = 'PARSE_FAILED'
        AND parse_failed_at IS NOT NULL
        AND parse_failure_code IS NOT NULL
        AND parsed_at IS NULL)
    OR (status IN ('UPLOADED', 'PARSING')
        AND parsed_at IS NULL
        AND parse_failed_at IS NULL
        AND parse_failure_code IS NULL)
    OR (status IN ('INDEXING', 'INDEXED', 'INDEX_FAILED')
        AND parsed_at IS NOT NULL
        AND parse_failed_at IS NULL
        AND parse_failure_code IS NULL)
);

-- ④ 状态与索引字段必须严格对应（与领域层的字段矩阵一一对应）
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_index_state CHECK (
    (status = 'INDEXING'
        AND index_started_at IS NOT NULL
        AND embedding_provider IS NOT NULL
        AND embedding_model IS NOT NULL
        AND embedding_dimensions IS NOT NULL
        AND indexed_at IS NULL
        AND index_failed_at IS NULL
        AND index_failure_code IS NULL)
    OR (status = 'INDEXED'
        AND index_started_at IS NOT NULL
        AND embedding_provider IS NOT NULL
        AND embedding_model IS NOT NULL
        AND embedding_dimensions IS NOT NULL
        AND indexed_at IS NOT NULL
        AND index_failed_at IS NULL
        AND index_failure_code IS NULL)
    OR (status = 'INDEX_FAILED'
        AND index_started_at IS NOT NULL
        AND embedding_provider IS NOT NULL
        AND embedding_model IS NOT NULL
        AND embedding_dimensions IS NOT NULL
        AND index_failed_at IS NOT NULL
        AND index_failure_code IS NOT NULL
        AND indexed_at IS NULL)
    OR (status IN ('UPLOADED', 'PARSING', 'PARSED', 'PARSE_FAILED')
        AND index_started_at IS NULL
        AND indexed_at IS NULL
        AND index_failed_at IS NULL
        AND index_failure_code IS NULL
        AND embedding_provider IS NULL
        AND embedding_model IS NULL
        AND embedding_dimensions IS NULL)
);

-- ⑤ 维度只允许本项目配置的那一个值：向量列的宽度必须与元数据声明一致，
--    否则「检索时比较不同长度的向量」会在运行期才暴露。
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_embedding_dimensions CHECK (
    embedding_dimensions IS NULL OR embedding_dimensions = 1024);

-- ⑥ 完整时间链：落在文档时间窗内，且索引时间不得早于解析完成时间
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_index_timeline CHECK (
    (index_started_at IS NULL OR (index_started_at >= created_at AND index_started_at <= updated_at))
    AND (indexed_at IS NULL OR (indexed_at >= created_at AND indexed_at <= updated_at))
    AND (index_failed_at IS NULL OR (index_failed_at >= created_at AND index_failed_at <= updated_at))
    AND (index_started_at IS NULL OR parsed_at IS NULL OR index_started_at >= parsed_at)
    AND (indexed_at IS NULL OR index_started_at IS NULL OR indexed_at >= index_started_at)
    AND (index_failed_at IS NULL OR index_started_at IS NULL OR index_failed_at >= index_started_at)
);

-- ⑦ 按状态筛选是索引流程与后续检索阶段的常见入口
CREATE INDEX idx_knowledge_documents_status ON knowledge_documents (status, updated_at);
