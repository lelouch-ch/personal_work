package com.example.pwt.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL DDL 冒烟测试。
 *
 * <p>设计文档 8.6 要求用真实 MySQL 验证迁移：H2 的 MySQL 兼容模式不覆盖
 * ngram 全文索引、STORED 生成列、多值索引、CHECK 约束与 {@code FOR UPDATE SKIP LOCKED}。
 *
 * <p>支持两种后端，优先级从高到低：
 * <ol>
 *   <li>外部实例：设置 {@code -Dpwt.test.db.url=jdbc:mysql://host:3306/} 及
 *       {@code pwt.test.db.username} / {@code pwt.test.db.password}。测试在该实例上创建独立
 *       临时库 {@code pwt_ddl_test}，结束后删除，不影响业务库。</li>
 *   <li>Testcontainers：未配置外部实例时使用镜像 {@value #MYSQL_IMAGE}，
 *       并以启动参数固化设计文档 8.5 的 {@code ngram_token_size}。</li>
 * </ol>
 * 两者都不可用时整个测试类跳过，不阻塞构建。
 *
 * @author pwt
 */
class MysqlDdlMigrationTest {

    /** 与设计文档 8.3 声明的目标版本保持一致。 */
    private static final String MYSQL_IMAGE = "mysql:8.0.36";

    /** 临时库名，外部实例与容器均使用该名称。 */
    private static final String SCRATCH_DATABASE = "pwt_ddl_test";

    /** 迁移脚本位置。 */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** 本轮数据标记，用于测试账号命名，避免与库中既有数据冲突。 */
    private static final String RUN_TAG = UUID.randomUUID().toString().substring(0, 8);

    /** JDBC URL 附加参数：强制会话时区为 UTC，与 datetime(3) 存储约定一致。 */
    private static final String URL_PARAMETERS =
        "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";

    private static DataSource dataSource;

    private static MySQLContainer<?> container;

    /** 外部实例模式下用于收尾删库的管理连接 URL；容器模式为 {@code null}。 */
    private static String adminUrl;

    /** 外部实例模式的库用户名。 */
    private static String adminUser;

    /** 外部实例模式的库口令。 */
    private static String adminPassword;

    @BeforeAll
    static void prepareAndMigrate() throws SQLException {
        String externalUrl = System.getProperty("pwt.test.db.url");
        if (externalUrl == null || externalUrl.isBlank()) {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "未配置 -Dpwt.test.db.url 且本机无 Docker，跳过 MySQL DDL 冒烟测试");
            startContainer();
        } else {
            String username = System.getProperty("pwt.test.db.username", "root");
            String password = System.getProperty("pwt.test.db.password", "");
            adminUrl = (externalUrl.endsWith("/") ? externalUrl : externalUrl + "/") + "mysql" + URL_PARAMETERS;
            adminUser = username;
            adminPassword = password;
            useExternalDatabase(externalUrl, username, password);
        }

        Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
    }

    @AfterAll
    static void releaseResources() throws SQLException {
        if (container != null) {
            container.stop();
        }
        if (adminUrl != null) {
            // 临时库用完即删，不在共享实例上留残留对象
            try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE);
            }
            System.out.println("[ddl-test] 已删除临时库 " + SCRATCH_DATABASE);
        }
    }

    private static void startContainer() {
        container = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName(SCRATCH_DATABASE)
            .withUsername("pwt")
            .withPassword("pwt-test-password")
            // ngram_token_size 为只读参数，只能在容器启动时指定（设计文档 8.5）
            .withCommand("--ngram_token_size=2",
                "--innodb_ft_enable_stopword=OFF",
                "--transaction-isolation=READ-COMMITTED",
                "--binlog_format=ROW");
        container.start();
        dataSource = DataSourceBuilder.create()
            .driverClassName(container.getDriverClassName())
            .url(container.getJdbcUrl() + URL_PARAMETERS)
            .username(container.getUsername())
            .password(container.getPassword())
            .build();
    }

    private static void useExternalDatabase(String baseUrl, String username, String password) throws SQLException {
        String trimmed = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        try (Connection connection = DriverManager.getConnection(trimmed + "mysql" + URL_PARAMETERS,
            username, password); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE);
            statement.executeUpdate("CREATE DATABASE " + SCRATCH_DATABASE
                + " DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci");
        }
        System.out.println("[ddl-test] 使用外部实例临时库 " + trimmed + SCRATCH_DATABASE);
        dataSource = DataSourceBuilder.create()
            .driverClassName("com.mysql.cj.jdbc.Driver")
            .url(trimmed + SCRATCH_DATABASE + URL_PARAMETERS)
            .username(username)
            .password(password)
            .build();
    }

    /**
     * 校验 8.3 的 17 张业务表全部创建成功。
     *
     * @throws SQLException 查询失败
     */
    @Test
    void shouldCreateAllBusinessTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, "pt_%", null)) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).hasSize(17).containsExactlyInAnyOrder(
                "pt_user", "pt_agent_client", "pt_user_profile", "pt_resume", "pt_project", "pt_work_log",
                "pt_memory", "pt_memory_evidence", "pt_memory_review", "pt_skill", "pt_style_rule",
                "pt_constraint", "pt_decision", "pt_report", "pt_async_task", "pt_context_access_log",
                "pt_audit_log");
        }
    }

    /**
     * 校验 idempotency_scope 生成列等价于 PostgreSQL 的部分唯一索引（设计文档 8.4 第 8 条）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldEnforceIdempotencyThroughGeneratedColumn() throws SQLException {
        String userId = insertUser("idem-1");
        String otherUserId = insertUser("idem-2");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // 幂等键为 NULL 时生成列为 NULL，不参与唯一判定
            assertThat(statement.executeUpdate(workLogSql(userId, "无幂等键 A", null))).isEqualTo(1);
            assertThat(statement.executeUpdate(workLogSql(userId, "无幂等键 B", null))).isEqualTo(1);
            assertThat(statement.executeUpdate(workLogSql(userId, "带幂等键", "key-1"))).isEqualTo(1);

            // 同用户重复幂等键必须被 uq_work_log_idempotency 拦截
            assertThatThrownBy(() -> statement.executeUpdate(workLogSql(userId, "重复提交", "key-1")))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);

            // 不同用户使用相同幂等键不得冲突：生成列已把 user_id 编入键值
            assertThat(statement.executeUpdate(workLogSql(otherUserId, "他人同键", "key-1"))).isEqualTo(1);
        }
    }

    /**
     * 校验“每个用户仅一份当前简历”的生成列唯一约束。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldAllowOnlyOneCurrentResumePerUser() throws SQLException {
        String userId = insertUser("resume-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(resumeSql(userId, 1, true))).isEqualTo(1);
            assertThat(statement.executeUpdate(resumeSql(userId, 2, false))).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate(resumeSql(userId, 3, true)))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .hasMessageContaining("uq_resume_current_per_user");
        }
    }

    /**
     * 校验任务去重键的生成列与唯一索引（V9，设计文档 17.3）。
     *
     * <p>只确认迁移能跑不够：去重键若没真正生效，同一条工作记录会被并发排队两次，
     * 表现为候选记忆成倍增长而不报错，属于静默劣化审核体验的问题。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldEnforceTaskDedupThroughGeneratedColumn() throws SQLException {
        String userId = insertUser("taskdedup-1");
        String otherUserId = insertUser("taskdedup-2");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // 无 dedup_ref 时生成列为 NULL，不参与唯一判定：同类任务可以并行排队
            assertThat(statement.executeUpdate(taskSql(userId, "EXTRACT_MEMORY", null))).isEqualTo(1);
            assertThat(statement.executeUpdate(taskSql(userId, "EXTRACT_MEMORY", null))).isEqualTo(1);
            assertThat(statement.executeUpdate(taskSql(userId, "EXTRACT_MEMORY", "log-1"))).isEqualTo(1);

            // 同用户、同类型、同业务对象重复排队必须被 uq_async_task_dedup 拦截
            assertThatThrownBy(() -> statement.executeUpdate(taskSql(userId, "EXTRACT_MEMORY", "log-1")))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .hasMessageContaining("uq_async_task_dedup");

            // 换一个业务对象、换一个任务类型都不算重复
            assertThat(statement.executeUpdate(taskSql(userId, "EXTRACT_MEMORY", "log-2"))).isEqualTo(1);
            assertThat(statement.executeUpdate(taskSql(userId, "REBUILD_SEARCH_INDEX", "log-1"))).isEqualTo(1);

            // 不同用户使用同一业务对象不得冲突：生成列已把 user_id 编入键值
            assertThat(statement.executeUpdate(taskSql(otherUserId, "EXTRACT_MEMORY", "log-1"))).isEqualTo(1);
        }
    }

    /**
     * 校验 ngram 解析器可召回中文内容（设计文档 8.5 的分词前提）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldSearchChineseWithNgramParser() throws SQLException {
        String userId = insertUser("fts-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(workLogSql(userId, "Feign 上传文件失败排查", null));

            try (ResultSet rs = statement.executeQuery(
                "SELECT title FROM pt_work_log WHERE user_id = '" + userId + "' "
                    + "AND MATCH(title, summary, content) AGAINST('上传 文件' IN BOOLEAN MODE)")) {
                assertThat(rs.next()).as("中文 2-gram 应命中").isTrue();
                assertThat(rs.getString("title")).isEqualTo("Feign 上传文件失败排查");
            }
        }
    }

    /**
     * 回归用例：插入时不提供 tags 列，必须由列默认值补成 {@code []}。
     *
     * <p>实测 MySQL 9.3.0：该列若声明为 {@code json NOT NULL DEFAULT (JSON_ARRAY())}，
     * 此插入报 {@code 3903 Invalid JSON value for CAST for functional index}——多值索引在
     * 列默认值生效前求值。改为可空后插入正常，且存储值仍为 {@code []}。见设计文档 8.4 第 9 条。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldInsertRowWithoutTagsColumnAndDefaultToEmptyArray() throws SQLException {
        String userId = insertUser("tags-default");
        String logId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO pt_work_log (id, user_id, source_type, title, occurred_at)"
                + " VALUES ('" + logId + "', '" + userId + "', 'manual', '不提供标签列', UTC_TIMESTAMP(3))");

            try (ResultSet rs = statement.executeQuery(
                "SELECT tags IS NULL, JSON_TYPE(tags), tags FROM pt_work_log WHERE id = '" + logId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("缺列插入应由默认值补为非 NULL").isZero();
                assertThat(rs.getString(2)).isEqualTo("ARRAY");
                assertThat(rs.getString(3)).isEqualTo("[]");
            }
        }
    }

    /**
     * 校验 tags 多值索引可被 {@code MEMBER OF} 使用，并锁定其大小写语义（设计文档 8.4 第 9 条）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldQueryTagsWithMultivaluedIndex() throws SQLException {
        String userId = insertUser("tag-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(workLogSql(userId, "标签检索", null));
            statement.executeUpdate("UPDATE pt_work_log SET tags = JSON_ARRAY('asr', 'llm') WHERE user_id = '"
                + userId + "'");

            try (ResultSet rs = statement.executeQuery(
                "SELECT title FROM pt_work_log WHERE user_id = '" + userId + "' AND 'asr' MEMBER OF (tags)")) {
                assertThat(rs.next()).as("MEMBER OF 应命中多值索引").isTrue();
            }
            try (ResultSet rs = statement.executeQuery(
                "SELECT title FROM pt_work_log WHERE user_id = '" + userId + "' AND 'nonexistent' MEMBER OF (tags)")) {
                assertThat(rs.next()).as("未命中标签不应返回数据").isFalse();
            }
            // 实测多值索引按二进制比较，大小写敏感：库中存 'asr' 时查询 'ASR' 不命中。
            // 因此标签必须在写入端归一，否则同一语义标签会以多种形式共存并静默漏检。
            try (ResultSet rs = statement.executeQuery(
                "SELECT title FROM pt_work_log WHERE user_id = '" + userId + "' AND 'ASR' MEMBER OF (tags)")) {
                assertThat(rs.next()).as("大写查询不应命中小写存储的标签").isFalse();
            }
        }
    }

    /**
     * 校验 {@code CHECK} 约束在 MySQL 8 上真实生效（设计文档 8.1 用 varchar + CHECK 替代 ENUM）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldRejectIllegalEnumValues() throws SQLException {
        String userId = insertUser("check-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO pt_memory (id, user_id, memory_type, content, risk_level) VALUES ('"
                    + UUID.randomUUID() + "', '" + userId + "', 'fact', '内容', 'L9')"))
                // MySQL 自动命名 CHECK 约束（形如 pt_memory_chk_2），只断言约束确实拒绝了写入
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Check constraint");
        }
    }

    /**
     * 回归用例：原始用户输入必须走自然语言模式，不能用布尔模式（设计文档 11.3、8.4 第 10 条）。
     *
     * <p>实测 MySQL 9.3.0：布尔模式将输入里的 {@code ( + - "} 当作检索运算符解析，
     * 未闭合的括号会让查询静默返回 0 行而不是报错。工作记录检索的入参是用户随手输入的原文，
     * 因此 {@code WorkLogMapper#searchFullText} 固定使用自然语言模式；布尔模式仅在
     * Context Builder 自行组装受控检索串时使用。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldLoseRecallWhenRawInputGoesThroughBooleanMode() throws SQLException {
        String userId = insertUser("fts-mode");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(workLogSql(userId, "上传文件失败排查", null));

            assertThat(countMatch(statement, userId, "(上传", true)).as("布尔模式下未闭合括号会静默漏检").isZero();
            assertThat(countMatch(statement, userId, "(上传", false)).as("自然语言模式把输入整体按词处理").isEqualTo(1);

            assertThat(countMatch(statement, userId, "上传 文件", true)).isEqualTo(1);
            assertThat(countMatch(statement, userId, "上传 文件", false)).isEqualTo(1);
        }
    }

    /**
     * 统计全文检索命中数。
     *
     * @param statement 语句对象
     * @param userId    用户过滤条件
     * @param phrase    检索串
     * @param booleanMode true 使用布尔模式
     * @return 命中行数
     * @throws SQLException 数据库访问失败
     */
    private static int countMatch(Statement statement, String userId, String phrase, boolean booleanMode)
        throws SQLException {
        String mode = booleanMode ? "IN BOOLEAN MODE" : "IN NATURAL LANGUAGE MODE";
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM pt_work_log WHERE user_id = '"
            + userId + "' AND MATCH(title, summary, content) AGAINST('" + phrase + "' " + mode + ")")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    /**
     * 校验标签检索确实走多值索引，而不是退化为全表扫描。
     *
     * <p>索引存在但未被使用不会报错，只会让查询变慢，因此必须用 {@code EXPLAIN} 断言。
     * 同时验证 {@code JSON_UNQUOTE(JSON_EXTRACT(...))} 写法不走索引（设计文档 8.4 第 9 条）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldUseMultivaluedIndexForTagLookup() throws SQLException {
        String userId = insertUser("tag-plan");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (int i = 0; i < 200; i++) {
                statement.executeUpdate(workLogSql(userId, "批量标签行 " + i, null));
            }
            // 每行写入互不相同的标签，仅一行含 'asr'：保证选择率约 1/200，
            // 否则匹配行占比过高时优化器选全表扫描才是正确行为，断言会变得不稳定
            statement.executeUpdate("UPDATE pt_work_log SET tags = JSON_ARRAY(CONCAT('tag-', id)) WHERE user_id = '"
                + userId + "'");
            statement.executeUpdate("UPDATE pt_work_log SET tags = JSON_ARRAY('asr') WHERE user_id = '"
                + userId + "' LIMIT 1");
            // ANALYZE TABLE 会返回结果集，必须用 execute 而非 executeUpdate
            statement.execute("ANALYZE TABLE pt_work_log");

            try (ResultSet rs = statement.executeQuery(
                "EXPLAIN SELECT id FROM pt_work_log WHERE 'asr' MEMBER OF (tags)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("key")).as("MEMBER OF 应命中 idx_work_log_tags")
                    .isEqualTo("idx_work_log_tags");
            }
            try (ResultSet rs = statement.executeQuery(
                "EXPLAIN SELECT id FROM pt_work_log WHERE JSON_UNQUOTE(JSON_EXTRACT(tags, '$[0]')) = 'asr'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("key")).as("JSON_EXTRACT 等值比较不应命中索引").isNull();
            }
        }
    }

    /**
     * 校验任务领取语句可使用 {@code FOR UPDATE SKIP LOCKED}（设计文档 17.2）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldClaimPendingTasksWithSkipLocked() throws SQLException {
        String userId = insertUser("task-1");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO pt_async_task (id, user_id, task_type, status) VALUES ('"
                    + UUID.randomUUID() + "', '" + userId + "', 'EXTRACT_MEMORY', 'pending')");
            }
            connection.commit();

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                     "SELECT id FROM pt_async_task WHERE status = 'pending' "
                         + "AND next_retry_at <= UTC_TIMESTAMP(3) ORDER BY created_at LIMIT 10 "
                         + "FOR UPDATE SKIP LOCKED")) {
                assertThat(rs.next()).as("应能领取到待执行任务").isTrue();
            }
            connection.rollback();
        }
    }

    /**
     * 校验外键约束在业务表上生效，悬挂引用不会产生脏数据。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldRejectUnknownProjectReference() throws SQLException {
        String userId = insertUser("fk-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO pt_work_log (id, user_id, project_id, source_type, title, occurred_at) VALUES ('"
                    + UUID.randomUUID() + "', '" + userId + "', '" + UUID.randomUUID()
                    + "', 'manual', '悬挂引用', UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    /**
     * 回归用例：审计表在缺少对应用户行时也必须可写。
     *
     * <p>{@code pt_audit_log} 与 {@code pt_context_access_log} 不设 user_id 外键：审计走独立事务，
     * 保留外键时会对业务事务尚未提交的父行加共享锁，自等到 {@code 1205 Lock wait timeout}。
     * 见设计文档 8.4 第 13 条与 ADR-022。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldInsertAuditRowWithoutReferencedUser() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("INSERT INTO pt_audit_log"
                + " (id, user_id, actor_type, action, resource_type) VALUES ('" + UUID.randomUUID() + "', '"
                + UUID.randomUUID() + "', 'system', 'regression.no_fk', 'probe')")).isEqualTo(1);

            try (ResultSet rs = statement.executeQuery(
                "SELECT constraint_name FROM information_schema.key_column_usage"
                    + " WHERE table_schema = DATABASE() AND table_name = 'pt_audit_log'"
                    + " AND referenced_table_name IS NOT NULL")) {
                assertThat(rs.next()).as("pt_audit_log 不应保留外键").isFalse();
            }
        }
    }

    /**
     * 校验字符集符合部署基线（设计文档 8.5）。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldUseUtf8mb4Schema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                "SELECT @@character_set_database, @@collation_database")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("utf8mb4");
                assertThat(rs.getString(2)).startsWith("utf8mb4_");
            }
        }
    }

    /**
     * 校验 ngram 分词粒度满足中文检索要求（设计文档 8.5）。
     *
     * <p>该参数为服务器只读配置：外部实例需与部署基线一致，容器后端由启动参数保证。
     * 不满足时跳过而非失败，避免开发机上的 MySQL 配置差异阻塞构建。
     *
     * @throws SQLException 数据库访问失败
     */
    @Test
    void shouldUseDocumentedNgramTokenSize() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW VARIABLES LIKE 'ngram_token_size'")) {
            assertThat(rs.next()).as("ngram_token_size 应可查询").isTrue();
            assumeTrue(rs.getInt(2) == 2,
                "实例 ngram_token_size 为 " + rs.getInt(2) + "，与设计文档 8.5 的基线 2 不一致，跳过");
        }
    }

    /**
     * 创建本轮测试专用的最小用户记录。
     *
     * @param label 用例标识，用于生成不冲突的登录名
     * @return 用户 ID
     * @throws SQLException 数据库访问失败
     */
    private static String insertUser(String label) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO pt_user (id, username, display_name, status) VALUES ('"
                + id + "', '" + label + "-" + RUN_TAG + "', '测试用户', 'active')");
        }
        return id;
    }

    /**
     * 组装工作记录插入语句。
     *
     * @param userId          所属用户
     * @param title           标题
     * @param idempotencyKey  幂等键，为空时省略该列
     * @return INSERT 语句
     */
    private static String workLogSql(String userId, String title, String idempotencyKey) {
        boolean keyed = idempotencyKey != null;
        return "INSERT INTO pt_work_log (id, user_id, source_type, title, occurred_at"
            + (keyed ? ", idempotency_key" : "") + ") VALUES ('" + UUID.randomUUID() + "', '" + userId
            + "', 'manual', '" + title + "', UTC_TIMESTAMP(3)"
            + (keyed ? ", '" + idempotencyKey + "'" : "") + ")";
    }

    /**
     * 组装简历插入语句。
     *
     * @param userId   所属用户
     * @param version  版本号
     * @param current  是否为当前版本
     * @return INSERT 语句
     */
    private static String resumeSql(String userId, int version, boolean current) {
        return "INSERT INTO pt_resume (id, user_id, version, title, content_md, is_current) VALUES ('"
            + UUID.randomUUID() + "', '" + userId + "', " + version + ", '简历 v" + version
            + "', '# 内容', " + (current ? 1 : 0) + ")";
    }

    /**
     * 组装异步任务插入语句。
     *
     * @param userId   所属用户
     * @param taskType 任务类型
     * @param dedupRef 去重引用，为空时省略该列
     * @return INSERT 语句
     */
    private static String taskSql(String userId, String taskType, String dedupRef) {
        boolean keyed = dedupRef != null;
        return "INSERT INTO pt_async_task (id, user_id, task_type"
            + (keyed ? ", dedup_ref" : "") + ") VALUES ('" + UUID.randomUUID() + "', '" + userId
            + "', '" + taskType + "'"
            + (keyed ? ", '" + dedupRef + "'" : "") + ")";
    }
}
