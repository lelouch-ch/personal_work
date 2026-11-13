-- Personal Work Twin: V7__create_report_task_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_report (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_type     varchar(30) NOT NULL
                    CHECK (report_type IN ('weekly','learning','trend')),
    title           varchar(300) NOT NULL,
    period_start    date DEFAULT NULL,
    period_end      date DEFAULT NULL,
    content_json    json NOT NULL DEFAULT (JSON_OBJECT()),
    content_md      mediumtext,
    source_refs     json NOT NULL DEFAULT (JSON_ARRAY()),
    model_metadata  json NOT NULL DEFAULT (JSON_OBJECT()),
    status          varchar(20) NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','confirmed','archived')),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_report_user_type_created (user_id, report_type, created_at DESC),
    CONSTRAINT fk_report_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;


CREATE TABLE pt_async_task (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type       varchar(50) NOT NULL,
    payload         json NOT NULL DEFAULT (JSON_OBJECT()),
    status          varchar(20) NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','running','succeeded','failed','cancelled')),
    retry_count     int NOT NULL DEFAULT 0,
    max_retries     int NOT NULL DEFAULT 5,
    next_retry_at   datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at       datetime(3) DEFAULT NULL,
    locked_by       varchar(150) DEFAULT NULL,
    last_error      text,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_async_task_ready (status, next_retry_at, created_at),
    CONSTRAINT fk_async_task_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;
