-- Personal Work Twin: V1__create_identity_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_user (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    username        varchar(100) NOT NULL UNIQUE,
    email           varchar(255) DEFAULT NULL,
    password_hash   varchar(255) DEFAULT NULL,
    display_name    varchar(150) NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'disabled', 'locked')),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      datetime(3) DEFAULT NULL
) ENGINE = InnoDB;


CREATE TABLE pt_agent_client (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_name     varchar(150) NOT NULL,
    client_type     varchar(40) NOT NULL DEFAULT 'agent',
    secret_hash     varchar(255) NOT NULL,
    scopes          json NOT NULL DEFAULT (JSON_ARRAY()),
    status          varchar(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'revoked', 'expired')),
    expires_at      datetime(3) DEFAULT NULL,
    last_seen_at    datetime(3) DEFAULT NULL,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_agent_client_user_status (user_id, status),
    CONSTRAINT fk_agent_client_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;
