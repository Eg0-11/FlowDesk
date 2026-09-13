-- FlowDesk 知识文档元数据表（FD-0008）。
--
-- 目标数据库为 PostgreSQL；本地开发与自动化测试使用 H2 的 PostgreSQL 兼容模式，
-- 因此只用两者共有的标准 SQL。
--
-- 注意分工：本表只存**元数据**，原始文件进内容存储（当前是本地文件系统适配器，
-- 见 docs/adr/0005-knowledge-document-upload-storage.md）。
-- content_key 是指向内容存储的不透明键，由服务端根据文档标识生成。

CREATE TABLE knowledge_documents (
    id                UUID                        NOT NULL,
    title             VARCHAR(200)                NOT NULL,
    original_filename VARCHAR(255)                NOT NULL,
    format            VARCHAR(32)                 NOT NULL,
    media_type        VARCHAR(128)                NOT NULL,
    size_bytes        BIGINT                      NOT NULL,
    sha256            CHAR(64)                    NOT NULL,
    content_key       VARCHAR(255)                NOT NULL,
    status            VARCHAR(32)                 NOT NULL,
    version           BIGINT                      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_knowledge_documents PRIMARY KEY (id),

    -- 一个内容键只能对应一个文档：内容键由文档标识生成，重复即意味着生成逻辑出了问题
    CONSTRAINT uk_knowledge_documents_content_key UNIQUE (content_key),

    -- 数值与枚举取值必须与领域模型一致
    CONSTRAINT ck_knowledge_documents_version_non_negative CHECK (version >= 0),
    CONSTRAINT ck_knowledge_documents_size_positive CHECK (size_bytes > 0),
    CONSTRAINT ck_knowledge_documents_format CHECK (format IN ('PDF', 'DOCX', 'MARKDOWN', 'TEXT')),
    CONSTRAINT ck_knowledge_documents_status CHECK (status IN ('UPLOADED')),

    -- 摘要必须是 64 位小写十六进制（用 LOWER 比较而不是正则，PostgreSQL 与 H2 语法一致）
    CONSTRAINT ck_knowledge_documents_sha256_length CHECK (char_length(sha256) = 64),
    CONSTRAINT ck_knowledge_documents_sha256_lowercase CHECK (sha256 = lower(sha256)),

    -- 标题与文件名不能是空字符串或空白
    CONSTRAINT ck_knowledge_documents_title_not_blank CHECK (char_length(trim(title)) > 0),
    CONSTRAINT ck_knowledge_documents_filename_not_blank CHECK (char_length(trim(original_filename)) > 0),

    -- 时间链
    CONSTRAINT ck_knowledge_documents_timeline CHECK (created_at <= updated_at)
);

-- 相同的文件内容允许作为不同的逻辑文档上传，因此 sha256 只建**普通索引**（用于将来去重查询），
-- 刻意不建唯一约束。
CREATE INDEX idx_knowledge_documents_sha256 ON knowledge_documents (sha256);
CREATE INDEX idx_knowledge_documents_created_at ON knowledge_documents (created_at);
