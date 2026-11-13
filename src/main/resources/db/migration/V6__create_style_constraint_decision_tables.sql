-- Personal Work Twin: V6__create_style_constraint_decision_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_style_rule (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rule_key        varchar(120) NOT NULL,
    rule_value      json NOT NULL,
    confidence      decimal(5,4) DEFAULT NULL CHECK (confidence >= 0 AND confidence <= 1),
    status          varchar(20) NOT NULL DEFAULT 'candidate'
                    CHECK (status IN ('candidate','approved','archived')),
    source_count    int NOT NULL DEFAULT 1 CHECK (source_count >= 1),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private','agent','public')),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_style_rule_user_key (user_id, rule_key),
    CONSTRAINT fk_style_rule_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;


CREATE TABLE pt_constraint (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name            varchar(200) NOT NULL,
    rule            text NOT NULL,
    severity        varchar(20) NOT NULL DEFAULT 'normal'
                    CHECK (severity IN ('low','normal','high','critical')),
    scope           varchar(30) NOT NULL DEFAULT 'global',
    status          varchar(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','inactive','archived')),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private','agent','public')),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_constraint_user_status (user_id, status),
    CONSTRAINT fk_constraint_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;


CREATE TABLE pt_decision (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id      char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    title           varchar(300) NOT NULL,
    decision        text NOT NULL,
    reason          text,
    alternatives    json NOT NULL DEFAULT (JSON_ARRAY()),
    status          varchar(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','reconsidered','superseded','archived')),
    decided_at      datetime(3) DEFAULT NULL,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_decision_user_project (user_id, project_id, decided_at DESC),
    CONSTRAINT fk_decision_user FOREIGN KEY (user_id) REFERENCES pt_user (id),
    CONSTRAINT fk_decision_project FOREIGN KEY (project_id) REFERENCES pt_project (id)
) ENGINE = InnoDB;
