-- FlowDesk 工单表。
--
-- 目标数据库为 PostgreSQL；本地开发与自动化测试使用 H2 的 PostgreSQL 兼容模式。
-- 两者都要能执行本脚本，因此不使用任何数据库专属枚举类型，所有取值约束用标准 CHECK 表达。
--
-- 这里刻意把领域规则重复表达为数据库约束：领域层是唯一权威，数据库是最后一道防线 ——
-- 任何绕过领域聚合的写入（迁移脚本、运维脚本、其它服务）都不可能写进非法状态。
-- 见 docs/adr/0002-ticket-persistence-spring-jdbc.md。

CREATE TABLE tickets (
    id           UUID                        NOT NULL,
    title        VARCHAR(200)                NOT NULL,
    description  VARCHAR(4000)               NOT NULL,
    category     VARCHAR(32)                 NOT NULL,
    priority     VARCHAR(8)                  NOT NULL,
    requester_id VARCHAR(64)                 NOT NULL,
    assignee_id  VARCHAR(64)                     NULL,
    status       VARCHAR(32)                 NOT NULL,
    resolution   VARCHAR(2000)                   NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    resolved_at  TIMESTAMP(6) WITH TIME ZONE     NULL,
    closed_at    TIMESTAMP(6) WITH TIME ZONE     NULL,
    version      BIGINT                      NOT NULL DEFAULT 0,

    CONSTRAINT pk_tickets PRIMARY KEY (id),

    -- 乐观锁版本不得为负
    CONSTRAINT ck_tickets_version_non_negative CHECK (version >= 0),

    -- 枚举取值必须与领域枚举完全一致
    CONSTRAINT ck_tickets_category CHECK (
        category IN ('ACCOUNT_ACCESS', 'NETWORK', 'HARDWARE', 'SOFTWARE', 'OTHER')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_tickets_status CHECK (
        status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),

    -- 必填文本不能是空字符串或普通空白
    CONSTRAINT ck_tickets_title_not_blank CHECK (char_length(trim(title)) > 0),
    CONSTRAINT ck_tickets_description_not_blank CHECK (char_length(trim(description)) > 0),
    CONSTRAINT ck_tickets_requester_not_blank CHECK (char_length(trim(requester_id)) > 0),
    CONSTRAINT ck_tickets_assignee_not_blank CHECK (
        assignee_id IS NULL OR char_length(trim(assignee_id)) > 0),
    CONSTRAINT ck_tickets_resolution_not_blank CHECK (
        resolution IS NULL OR char_length(trim(resolution)) > 0),

    -- 状态与 assignee / resolution / resolved_at / closed_at 的组合必须符合领域规则
    CONSTRAINT ck_tickets_status_state CHECK (
        (status = 'NEW'
            AND assignee_id IS NULL
            AND resolution IS NULL
            AND resolved_at IS NULL
            AND closed_at IS NULL)
        OR (status IN ('ASSIGNED', 'IN_PROGRESS')
            AND assignee_id IS NOT NULL
            AND resolution IS NULL
            AND resolved_at IS NULL
            AND closed_at IS NULL)
        OR (status = 'RESOLVED'
            AND assignee_id IS NOT NULL
            AND resolution IS NOT NULL
            AND resolved_at IS NOT NULL
            AND closed_at IS NULL)
        OR (status = 'CLOSED'
            AND assignee_id IS NOT NULL
            AND resolution IS NOT NULL
            AND resolved_at IS NOT NULL
            AND closed_at IS NOT NULL)
    ),

    -- 完整时间链：created_at <= resolved_at <= closed_at <= updated_at
    CONSTRAINT ck_tickets_timeline CHECK (
        created_at <= updated_at
        AND (resolved_at IS NULL
            OR (resolved_at >= created_at AND resolved_at <= updated_at))
        AND (closed_at IS NULL
            OR (resolved_at IS NOT NULL AND closed_at >= resolved_at AND closed_at <= updated_at))
    )
);

CREATE INDEX idx_tickets_status_updated_at ON tickets (status, updated_at);
CREATE INDEX idx_tickets_assignee_status ON tickets (assignee_id, status);
CREATE INDEX idx_tickets_created_at ON tickets (created_at);
