-- Personal Work Twin: V2__create_profile_resume_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_user_profile (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    headline        varchar(255) DEFAULT NULL,
    summary         text,
    profile_json    json NOT NULL DEFAULT (JSON_OBJECT()),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private', 'agent', 'public')),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;


CREATE TABLE pt_resume (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version         int NOT NULL,
    title           varchar(200) NOT NULL,
    content_md      mediumtext NOT NULL,
    resume_json     json NOT NULL DEFAULT (JSON_OBJECT()),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private', 'agent', 'public')),
    is_current      tinyint(1) NOT NULL DEFAULT 0,
    current_user_id char(36) CHARACTER SET ascii COLLATE ascii_bin
                    GENERATED ALWAYS AS (
                        IF(`is_current` = 1, `user_id`, NULL)
                    ) STORED,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_resume_version (user_id, version),
    UNIQUE KEY uq_resume_current_per_user (current_user_id),
    CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;
