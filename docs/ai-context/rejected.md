# 已放弃方案

试过或认真评估后放弃的做法，避免被重复尝试。多数条目有真实实测结果支撑，
括号内标出可复核的位置。

## 用 H2 / 内存库做 DDL 与检索测试

- Date: 2026-11-13
- Type: rejected-at-design
- Tried because: 免依赖、跑得快，CI 上不需要外部实例。
- Result: 改为在真实 MySQL 上跑 `MysqlDdlMigrationTest`（临时库 `pwt_ddl_test`，用完即删），
  外部实例不可用时按假设跳过而不是失败。
- Why abandoned: H2 的 MySQL 兼容模式不覆盖 JSON 列与多值索引、STORED 生成列唯一约束、
  ngram 全文检索、CHECK 约束的实际生效情况与 `FOR UPDATE SKIP LOCKED`；
  这些恰好是本项目最容易出错的地方，用 H2 会让测试通过但线上坏掉。
- Revisit if: 迁移到支持这些语义的测试容器方案（Testcontainers 路径已保留在根 `README.md`）。

## PostgreSQL 作为数据库基线

- Date: 2026-11-13
- Type: implemented-then-replaced
- Tried because: 初版设计（v1.0）选了 PostgreSQL，其 RLS 与部分索引本可简化隔离与约束实现。
- Result: 改为 MySQL，v1.0 文档保留为历史只读版本，v1.1 为当前基线。
- Why abandoned: 复用现有运维与 MyBatis-Plus 生态；MySQL 的 JSON、事务与全文检索满足 MVP 需要。
  代价是必须自己补偿 PostgreSQL 白送的能力：无 RLS → 多租户插件 + 业务层约束（ADR-020）；
  无部分索引 → STORED 生成列（ADR-021）。
- Revisit if: 需要真正的行级安全或多租户组织能力（ADR-017 明确 MVP 不建设）。

## MySQL 原生 `ENUM` 列类型

- Date: 2026-11-13
- Type: rejected-at-design
- Tried because: 写起来短，数据库侧天然限定取值。
- Result: 一律 `varchar + CHECK`（见 `sql_mode` 下的建表语句与 DDL 测试对非法取值的断言）。
- Why abandoned: `ENUM` 增删取值要改表结构且顺序敏感，取值集合也会与 Java 枚举产生
  隐式顺序耦合；`CHECK` 在 MySQL 8 真实生效，报错同样是约束冲突。
- Revisit if: 目标实例的 MySQL 版本低于 8.0.16（该版本前 `CHECK` 会被解析但不执行）。

## 用 `JSON_UNQUOTE(JSON_EXTRACT(tags, '$[0]'))` 查标签

- Date: 2026-11-13
- Type: implemented-then-replaced
- Tried because: 直观，一行就能取数组元素比对。
- Result: 改用 `#{tag} MEMBER OF (tags)` 命中多值索引。
- Why abandoned: 实测 `EXPLAIN` 显示这种写法的 `key` 为 `NULL`（全表扫描），
  而多值索引只在 `MEMBER OF` / `JSON_CONTAINS` 下生效。断言在
  `MysqlDdlMigrationTest#shouldQueryTagsWithMultivaluedIndex`。
- Revisit if: 无。

## `tags json NOT NULL DEFAULT (JSON_ARRAY())`

- Date: 2026-11-13
- Type: implemented-then-replaced
- Tried because: 想省掉可空判断，让列恒为数组。
- Result: 改为可空列，缺列插入时存储值仍为 `[]`。
- Why abandoned: 实测 MySQL 9.3.0 报 `3903 Invalid JSON value for CAST for functional index`——
  多值索引在列默认值生效之前求值。回归用例见同名 DDL 测试方法。
- Revisit if: 该实例升级到修复此顺序问题的版本，且愿意重跑 DDL 用例。

## MyBatis-Plus 全局逻辑删除

- Date: 2026-11-13
- Type: rejected-at-design
- Tried because: 表里已有 `deleted_at`，框架自带逻辑删除看起来是零成本。
- Result: 不启用，软删除条件由各 Repository 显式追加 `deleted_at IS NULL`。
- Why abandoned: 其 null 语义会渲染成 `deleted_at = null`，在 MySQL 下永不匹配，
  结果是查询静默返回空集。决策记在 `application.yml` 的注释里。
- Revisit if: 升级到修正 null 渲染的版本，并先用一条断言验证生成 SQL 确实是 `IS NULL`。

## 用布尔模式直接检索用户原始输入

- Date: 2026-11-13
- Type: rejected-at-design
- Tried because: 布尔模式支持 `+ -` 之类的运算符，看起来表达力更强、更适合关键词。
- Result: 原始输入走 `IN NATURAL LANGUAGE MODE`；布尔模式只用于 Context Builder
  自行组装的受控检索串。
- Why abandoned: 实测对含“上传文件失败排查”的数据执行 `AGAINST('(上传' IN BOOLEAN MODE)`
  返回 0 行且不报错，同语句改自然语言模式命中 1 行；用户输入里的括号、加减号
  会静默改变语义。回归见 `MysqlDdlMigrationTest#shouldLoseRecallWhenRawInputGoesThroughBooleanMode`。
- Revisit if: 输入在进入前保证被完整清洗并转义，且愿意维护转义规则。

## 引入 Spring Security 做认证与授权

- Date: 2026-11-13
- Type: rejected-at-design
- Tried because: 框架自带过滤器链、认证与表达式授权，看起来省代码。
- Result: 只依赖 `spring-security-crypto` 做哈希；认证由自建拦截器完成，
  授权由各 application 服务显式调用 scope 校验。
- Why abandoned: 本项目是 Owner JWT 与 Agent API Key 双轨，且权限判定必须落在
  “每个动作对应的 scope”这个业务粒度上（ADR-012）；过滤器链式的全局授权表达式
  容易给出与业务不一致的默认放行，也带来一整套不易排查的配置面。
  依赖选择记在 `pom.xml` 注释与设计文档 14.2。
- Revisit if: 需要 OAuth2 资源服务器、会话管理或方法级安全这类框架能力成为硬需求。

## 按项目过滤记忆时只走“证据 → 工作记录”两跳，不给 `pt_memory` 加列

- Date: 2026-11-18
- Type: rejected-at-design
- Tried because: 不用改表结构，`pt_memory_evidence → pt_work_log.project_id` 就能推出归属。
- Result: V10 给 `pt_memory` 加 `project_id` 列，由来源工作记录在落库前带入。
- Why abandoned: Owner 手动提交、以及提取时未带证据的记忆根本没有证据行，
  永远归属不到项目，在按项目过滤时静默消失；漏召回比多召回更难被发现。
  两跳查询还需要新增索引支撑。见设计文档 8.4 第 21 条。
- Revisit if: 明确接受“按项目查询时只看有证据的记忆”，那时可回退这一列。

## 预算不足时“一旦有条目放不下就整段停止填充”

- Date: 2026-11-18
- Type: implemented-then-replaced
- Tried because: 语义简单，避免用低相关条目填缝让结果更难解释。
- Result: 改为跳过放不下的条目继续尝试下一条，并照旧标记该段被截断。
- Why abandoned: 端到端实测到预算 500 时注入 0 条、而估算只用了 117 token——
  最高分那条往往最长（没写摘要时注入正文），它一超预算就把整段清空，
  Agent 明明还有预算可用却空手而归。修正后同场景注入 5 条。
- Revisit if: 上下文段之间改为严格互斥的分池预算，此时“本池满即停”才重新合理。
