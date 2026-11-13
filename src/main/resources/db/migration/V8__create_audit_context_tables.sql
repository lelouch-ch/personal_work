-- Personal Work Twin: V8__create_audit_context_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_context_access_log (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_client_id char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    request_id      varchar(100) DEFAULT NULL,
    task_summary    varchar(1000) DEFAULT NULL,
    memory_ids      json NOT NULL DEFAULT (JSON_ARRAY()),
    skill_ids       json NOT NULL DEFAULT (JSON_ARRAY()),
    token_budget    int DEFAULT NULL,
    token_estimate  int DEFAULT NULL,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_context_log_user_created (user_id, created_at DESC),
    KEY idx_context_log_agent (agent_client_id)
) ENGINE = InnoDB;


CREATE TABLE pt_audit_log (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    actor_type      varchar(20) NOT NULL
                    CHECK (actor_type IN ('owner','agent','admin','system','anonymous')),
    actor_id        char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    action          varchar(100) NOT NULL,
    resource_type   varchar(80) NOT NULL,
    resource_id     char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    request_id      varchar(100) DEFAULT NULL,
    before_data     json DEFAULT NULL,
    after_data      json DEFAULT NULL,
    ip_address      varchar(45) DEFAULT NULL,
    user_agent      varchar(500) DEFAULT NULL,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_audit_user_created (user_id, created_at DESC),
    KEY idx_audit_resource (resource_type, resource_id, created_at DESC)
) ENGINE = InnoDB;
