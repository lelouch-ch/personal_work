-- Personal Work Twin: V5__create_skill_tables.sql
-- 来源：Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md 8.3 MVP MySQL DDL


CREATE TABLE pt_skill (
    id                  char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id             char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name                varchar(200) NOT NULL,
    slug                varchar(200) NOT NULL,
    description         text,
    content_md          mediumtext NOT NULL,
    version             int NOT NULL DEFAULT 1,
    status              varchar(20) NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft','published','archived')),
    visibility          varchar(20) NOT NULL DEFAULT 'private'
                        CHECK (visibility IN ('private','agent','public')),
    source_memory_ids   json NOT NULL DEFAULT (JSON_ARRAY()),
    created_at          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_skill_user_slug_version (user_id, slug, version),
    KEY idx_skill_user_status (user_id, status),
    FULLTEXT KEY ft_skill_text (name, description, content_md) WITH PARSER ngram,
    CONSTRAINT fk_skill_user FOREIGN KEY (user_id) REFERENCES pt_user (id)
) ENGINE = InnoDB;
