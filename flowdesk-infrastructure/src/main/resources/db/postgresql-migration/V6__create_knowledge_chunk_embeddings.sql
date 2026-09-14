-- FlowDesk 切片向量表（FD-0010，RAG 3/6）—— PostgreSQL 专用。
--
-- 这个脚本只在 PostgreSQL 上执行：它需要 pgvector 扩展与 vector(1024) 列类型，
-- H2 既没有这些类型也没有 CREATE EXTENSION。默认 profile（H2）只加载
-- classpath:db/migration（V1~V5），postgres profile 额外加载本目录，见 application-postgres.yml。
--
-- 为什么<b>不</b>使用 Spring AI 的 PgVectorStore 自动建表：
-- 1. 它建的是通用 vector_store 表，只有 id/content/metadata/embedding，没有与
--    knowledge_document_chunks 的外键，因此「向量是否与当前切片一致」无法由数据库证明；
-- 2. 它不支持本项目的状态 CAS 与「写入向量 + 推进文档状态」同一事务的语义；
-- 3. 它的 initialize-schema 会在启动时按自己的规则改表结构，与 Flyway 的所有权冲突。
-- 因此本项目用「Flyway 管理表结构 + 自定义 JDBC 适配器写入」，见 ADR 0007。

-- ① pgvector 扩展：向量类型与距离算子都来自它
CREATE EXTENSION IF NOT EXISTS vector;

-- ② 切片向量表
--
-- 主键与切片表一致：(document_id, chunk_index)。
-- 复合外键直接指向 knowledge_document_chunks(document_id, chunk_index)：向量<b>不可能</b>
-- 存在没有对应切片的行；删除文档时切片级联删除，向量随之级联删除。
CREATE TABLE knowledge_document_chunk_embeddings (
    document_id          UUID                        NOT NULL,
    chunk_index          INTEGER                     NOT NULL,
    chunk_sha256         CHAR(64)                    NOT NULL,
    embedding            vector(1024)                NOT NULL,
    provider             VARCHAR(32)                 NOT NULL,
    model                VARCHAR(128)                NOT NULL,
    embedding_dimensions INTEGER                     NOT NULL,
    created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_knowledge_document_chunk_embeddings PRIMARY KEY (document_id, chunk_index),

    CONSTRAINT fk_knowledge_document_chunk_embeddings_chunk FOREIGN KEY (document_id, chunk_index)
        REFERENCES knowledge_document_chunks (document_id, chunk_index) ON DELETE CASCADE,

    CONSTRAINT ck_knowledge_document_chunk_embeddings_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_document_chunk_embeddings_dimensions CHECK (embedding_dimensions = 1024),
    -- 列宽与元数据声明必须一致：两者任何一个被改坏都会在写入时立刻暴露
    CONSTRAINT ck_knowledge_document_chunk_embeddings_vector_dims CHECK (
        vector_dims(embedding) = embedding_dimensions),
    CONSTRAINT ck_knowledge_document_chunk_embeddings_sha256_length CHECK (char_length(chunk_sha256) = 64),
    CONSTRAINT ck_knowledge_document_chunk_embeddings_sha256_lowercase CHECK (
        chunk_sha256 = lower(chunk_sha256))
);

-- ③ HNSW 余弦索引：后续检索阶段（向量相似度查询）使用 vector_cosine_ops。
--    本阶段只写入、不查询，但索引必须与数据一起建立 —— 事后补建会让已有数据重新排序，
--    而 HNSW 的构建开销随数据量增长。
CREATE INDEX idx_knowledge_document_chunk_embeddings_hnsw
    ON knowledge_document_chunk_embeddings USING hnsw (embedding vector_cosine_ops);
