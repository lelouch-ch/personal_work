-- Personal Work Twin: V3__create_project_work_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_project (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name            varchar(200) NOT NULL,
    description     text,
    status          varchar(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'paused', 'completed', 'archived')),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private', 'agent', 'public')),
    started_at      date DEFAULT NULL,
    ended_at        date DEFAULT NULL,
    metadata        json NOT NULL DEFAULT (JSON_OBJECT()),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      datetime(3) DEFAULT NULL,
    KEY idx_project_user_status (user_id, status),
    CONSTRAINT fk_project_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;


CREATE TABLE pt_work_log (
    id                  char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id             char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id          char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    agent_client_id     char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    source_type         varchar(20) NOT NULL
                        CHECK (source_type IN ('manual', 'agent', 'import')),
    title               varchar(300) NOT NULL,
    summary             mediumtext,
    content             mediumtext,
    work_type           varchar(40) NOT NULL DEFAULT 'general',
    visibility          varchar(20) NOT NULL DEFAULT 'private'
                        CHECK (visibility IN ('private', 'agent', 'public')),
    status              varchar(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'archived', 'deleted')),
    occurred_at         datetime(3) NOT NULL,
    tags                json DEFAULT (JSON_ARRAY()),
    idempotency_key     varchar(150) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    idempotency_scope   varchar(200) CHARACTER SET ascii COLLATE ascii_bin
                        GENERATED ALWAYS AS (
                            IF(`idempotency_key` IS NULL,
                               NULL,
                               CONCAT(`user_id`, '#', `idempotency_key`))
                        ) STORED,
    created_at          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_work_log_idempotency (idempotency_scope),
    KEY idx_work_log_user_occurred (user_id, occurred_at DESC),
    KEY idx_work_log_user_project (user_id, project_id, occurred_at DESC),
    KEY idx_work_log_tags ((CAST(`tags` AS char(64) ARRAY))),
    FULLTEXT KEY ft_work_log_text (title, summary, content) WITH PARSER ngram,
    CONSTRAINT fk_work_log_user FOREIGN KEY (user_id) REFERENCES pt_user (id),
    CONSTRAINT fk_work_log_project FOREIGN KEY (project_id) REFERENCES pt_project (id),
    CONSTRAINT fk_work_log_agent FOREIGN KEY (agent_client_id) REFERENCES pt_agent_client (id)
) ENGINE = InnoDB;
