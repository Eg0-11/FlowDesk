-- FlowDesk 文档解析与确定性切片（FD-0009）。
--
-- 目标数据库为 PostgreSQL；本地开发与自动化测试使用 H2 的 PostgreSQL 兼容模式，
-- 因此只用两者共有的标准 SQL。V1~V3 已发布，本脚本<b>不修改</b>它们。

-- ① 解析结果字段（全部可空：只有对应状态才允许非空）
ALTER TABLE knowledge_documents ADD COLUMN parsed_at TIMESTAMP(6) WITH TIME ZONE NULL;
ALTER TABLE knowledge_documents ADD COLUMN parse_failed_at TIMESTAMP(6) WITH TIME ZONE NULL;
ALTER TABLE knowledge_documents ADD COLUMN parse_failure_code VARCHAR(32) NULL;

-- ② 状态取值扩展：UPLOADED / PARSING / PARSED / PARSE_FAILED
ALTER TABLE knowledge_documents DROP CONSTRAINT ck_knowledge_documents_status;
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_status CHECK (
    status IN ('UPLOADED', 'PARSING', 'PARSED', 'PARSE_FAILED'));

-- ③ 状态与解析字段必须严格对应（不允许「各说各话」的快照）
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
);

-- ④ 解析字段的时间必须落在文档自己的时间窗内
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_parse_timeline CHECK (
    (parsed_at IS NULL OR (parsed_at >= created_at AND parsed_at <= updated_at))
    AND (parse_failed_at IS NULL OR (parse_failed_at >= created_at AND parse_failed_at <= updated_at))
);

-- ⑤ 切片表
--
-- 主键是 (document_id, chunk_index)：同一文档内序号唯一，天然表达「按文档顺序读取」。
-- 外键指向文档并在删除文档时级联删除切片：切片没有任何独立生命周期。
CREATE TABLE knowledge_document_chunks (
    document_id      UUID                        NOT NULL,
    chunk_index      INTEGER                     NOT NULL,
    content          VARCHAR(4000)              NOT NULL,
    code_point_count INTEGER                     NOT NULL,
    sha256           CHAR(64)                    NOT NULL,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_knowledge_document_chunks PRIMARY KEY (document_id, chunk_index),

    CONSTRAINT fk_knowledge_document_chunks_document FOREIGN KEY (document_id)
        REFERENCES knowledge_documents (id) ON DELETE CASCADE,

    CONSTRAINT ck_knowledge_document_chunks_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_document_chunks_content CHECK (char_length(content) > 0),
    CONSTRAINT ck_knowledge_document_chunks_code_points CHECK (code_point_count > 0),
    CONSTRAINT ck_knowledge_document_chunks_sha256_length CHECK (char_length(sha256) = 64),
    CONSTRAINT ck_knowledge_document_chunks_sha256_lowercase CHECK (sha256 = lower(sha256))
);

-- 按文档顺序读取（后续向量化阶段的分页/流式读取端口会用到）。
-- 注意：主键 (document_id, chunk_index) 已经提供了同样的顺序，这里的独立索引是为了
-- 让「只按 document_id 过滤 + 按 chunk_index 排序」的查询路径明确可见，代价很低。
CREATE INDEX idx_knowledge_document_chunks_document ON knowledge_document_chunks (document_id, chunk_index);
