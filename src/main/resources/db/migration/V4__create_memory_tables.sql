-- Personal Work Twin: V4__create_memory_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_memory (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_type     varchar(20) NOT NULL
                    CHECK (memory_type IN ('fact','preference','experience','decision','constraint','goal')),
    content         mediumtext NOT NULL,
    summary         varchar(1000) DEFAULT NULL,
    normalized_hash varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    confidence      decimal(5,4) DEFAULT NULL CHECK (confidence >= 0 AND confidence <= 1),
    importance      smallint NOT NULL DEFAULT 3 CHECK (importance BETWEEN 1 AND 5),
    risk_level      varchar(5) NOT NULL DEFAULT 'L1'
                    CHECK (risk_level IN ('L0','L1','L2','L3')),
    visibility      varchar(20) NOT NULL DEFAULT 'private'
                    CHECK (visibility IN ('private','agent','public')),
    status          varchar(20) NOT NULL DEFAULT 'candidate'
                    CHECK (status IN ('candidate','approved','rejected','archived','superseded')),
    source_type     varchar(20) NOT NULL DEFAULT 'agent'
                    CHECK (source_type IN ('manual','agent','import','system')),
    source_id       char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    version         int NOT NULL DEFAULT 1,
    supersedes_id   char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    valid_from      datetime(3) DEFAULT NULL,
    valid_until     datetime(3) DEFAULT NULL,
    expires_at      datetime(3) DEFAULT NULL,
    last_used_at    datetime(3) DEFAULT NULL,
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      datetime(3) DEFAULT NULL,
    KEY idx_memory_user_type_status (user_id, memory_type, status),
    KEY idx_memory_user_status_created (user_id, status, created_at DESC),
    KEY idx_memory_user_expiry (user_id, expires_at),
    KEY idx_memory_user_hash (user_id, normalized_hash),
    FULLTEXT KEY ft_memory_text (content, summary) WITH PARSER ngram,
    CONSTRAINT fk_memory_user FOREIGN KEY (user_id) REFERENCES pt_user (id),
    CONSTRAINT fk_memory_supersedes FOREIGN KEY (supersedes_id) REFERENCES pt_memory (id)
) ENGINE = InnoDB;


CREATE TABLE pt_memory_evidence (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_id       char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_log_id     char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    evidence_type   varchar(30) NOT NULL DEFAULT 'source_excerpt',
    excerpt         mediumtext,
    source_metadata json NOT NULL DEFAULT (JSON_OBJECT()),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_memory_evidence_memory (memory_id),
    KEY idx_memory_evidence_user (user_id),
    CONSTRAINT fk_evidence_user FOREIGN KEY (user_id) REFERENCES pt_user (id),
    CONSTRAINT fk_evidence_memory FOREIGN KEY (memory_id) REFERENCES pt_memory (id),
    CONSTRAINT fk_evidence_work_log FOREIGN KEY (work_log_id) REFERENCES pt_work_log (id)
) ENGINE = InnoDB;


CREATE TABLE pt_memory_review (
    id              char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id         char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_id       char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_type     varchar(30) NOT NULL,
    risk_level      varchar(5) NOT NULL CHECK (risk_level IN ('L0','L1','L2','L3')),
    decision        varchar(30) NOT NULL
                    CHECK (decision IN ('AUTO_APPROVE','PENDING_REVIEW','APPROVE','REJECT','MERGE','SUPERSEDE')),
    reason          text,
    reviewer_type   varchar(20) NOT NULL
                    CHECK (reviewer_type IN ('system','owner','admin')),
    reviewer_id     char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    rule_snapshot   json NOT NULL DEFAULT (JSON_OBJECT()),
    created_at      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_memory_review_user_created (user_id, created_at DESC),
    KEY idx_memory_review_memory (memory_id, created_at DESC),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES pt_user (id),
    CONSTRAINT fk_review_memory FOREIGN KEY (memory_id) REFERENCES pt_memory (id)
) ENGINE = InnoDB;
