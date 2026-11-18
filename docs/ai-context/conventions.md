# 约定

会改变后续代码写法的规则。多数条目来自已经真实发生过的故障，忽略它们的代价是静默错误而不是报错，
因此改动相关代码前必须逐条对照。

## 技术栈

- JDK 17、Spring Boot 3.2.2、MyBatis-Plus 3.5.6、Flyway（随 Boot）、MySQL Connector 随 Boot。
  版本由 `pom.xml` 的 parent 与 properties 锁定，不要单点升级某个 starter。
- MCP Java SDK `io.modelcontextprotocol.sdk:mcp` 0.11.3：选它是因为它是含 Streamable HTTP
  且仍为单 jar 的版本，字节码 major=61（Java 17）。它声明 jackson 2.17 / reactor 3.7，
  实际由 Boot 3.2.2 的 BOM 降为 2.15.3 / 3.6.2（`mvn dependency:tree` 已确认），
  升级 Boot 或 SDK 时必须重跑全量测试验证这组降级仍然可用。
- 主键一律由应用生成 UUIDv7（`common/util/Uuids`），列类型 `char(36) ascii_bin`。
  不用数据库自增、不用原生 UUID 默认排序。
- 时间统一 `datetime(3)` + UTC，连接串强制会话时区；不用 `timestamp` 类型。
- 枚举用 `varchar + CHECK`，不用 MySQL `ENUM`：便于迁移与取值校验，代价是取值必须
  在送入数据库前用 Java 枚举校验，否则报错是英文枚举名而不是中文提示。

## 项目特有规则（忽略即产生静默缺陷）

- **注解 SQL 不继承实体的 `@TableName(autoResultMap = true)`。** 凡 `@Select`/`@Update`
  选中 `json` 列，必须在 `@Results`/`@ResultMap` 里显式绑定 `JacksonTypeHandler`，
  否则该列被读成 `null` 且不报错。已踩两次：`pt_async_task.payload`、`pt_work_log.tags`。
- **`FOR UPDATE SKIP LOCKED` 的语句必须加 `@InterceptorIgnore(tenantLine = "true")`。**
  多租户插件会重建 SQL 并把锁子句挪到 `ORDER BY`/`LIMIT` 之前，生成非法语法；
  即使当前线程无主体上下文、插件不追加条件，它仍会走一次重建。
- **`LIKE ... ESCAPE` 的反斜杠在 Java 文本块里要写四层（`'\\\\'`）。** 写两层时落到 SQL 是
  `ESCAPE '\'`，MySQL 会把闭合引号当转义，吞掉后面的 `#{}` 占位符，报
  `Parameter index out of range`。已由 `MemoryMapperSqlTest` 用注解值断言锁死。
- **全文检索模式取决于输入来源。** 原始用户输入一律用 `IN NATURAL LANGUAGE MODE`
  （布尔模式下未闭合的括号会静默返回 0 行）；`IN BOOLEAN MODE` 只用于本系统自行组装的
  受控检索串，组装前必须剔除运算符字符，见 `context/domain/ContextQuery`。
- **单字查询必须保留 `LIKE` 兜底。** `ngram_token_size = 2` 下单字永远命中不了索引，
  空结果与“确实没有相关记忆”不可区分，召回路径要随响应返回（`recall_path`）。
- **落库之后再改实体字段不会写回数据库。** 实测踩过：记忆的 `project_id` 在 `insert`
  之后赋值，接口返回带值而库里是 `null`。需要落库的值必须在 `insert` 前赋好。
- **写断言时警惕可变实体 + ArgumentCaptor。** MyBatis mock 返回的是同一个对象引用，
  插入之后再读该字段看不到“插入时刻的值”，要用 `doAnswer` 在调用时刻捕获。
- **`updated_at` 由应用层显式赋值。** MyBatis-Plus 会把读到的旧值写回该列，
  只依赖 `ON UPDATE CURRENT_TIMESTAMP(3)` 会让它停在建行时刻。
- **JSON 列的写入走 MyBatis-Plus 自动生成的语句**（条件构造器或 `insert`/`updateById`），
  不要在注解 SQL 里手写 JSON 列的读写。
- **标签在写入端归一为小写。** 多值索引按二进制比较、大小写敏感，不归一会静默漏检。
- **审计与上下文访问日志表不设 `user_id` 外键，且在多租户免拦截白名单内。**
  它们用独立事务写入，外键会对未提交的父行加共享锁导致 1205 锁等待；
  条件与键必须由业务层显式给出，不能指望插件追加。
- **可见性判定只经 `identity/domain/AccessPolicy`。** 三档阶梯（private 仅 Owner、
  agent 需 scope、public 可匿名）与“非 Owner 不得写 public”都集中在那里，
  不要在查询里另写一套 `visibility IN (...)`。
- **`user_id` 只从凭证解析**，不接受客户端传入；跨用户访问统一按“不存在”处理，
  避免泄露资源存在性。
- **风险等级与审核结论由服务端计算**，不采信调用方或模型自报的置信度 / 类型 / 风险。
- **未配置的依赖要显式失败，不能伪造正常结果。** LLM 未接线时提取任务抛
  `LLM_PROVIDER_ERROR` 并重试，而不是返回“提取到 0 条候选”。
- **新增生产依赖前必须先征求用户同意并说明理由**；不加与既有能力重叠的框架
  （如 Spring Security 全家桶，本项目只取 `spring-security-crypto` 做哈希）。
- **挂在独立 Servlet 上的端点必须用 Filter 做认证。** Spring MVC 的拦截器只作用于 MVC 的
  `HandlerMapping`，对 MCP 这类自己注册的 Servlet 完全不生效，漏了就是接口全裸；
  凭证判定还要与 REST 共用同一个组件，否则同一个密钥会在一边能用、另一边被拒。
- **传输提供者是 `HttpServlet` 时必须显式注册到配置路径。** 只声明成普通 Bean，
  Boot 会兜底把它挂到 `/`，等于整站路径都交给它处理。
- **自己 `new ObjectMapper()` 就必须注册 `JavaTimeModule` 并关闭时间戳输出。**
  本项目的 DTO 大量使用 `Instant`，裸 mapper 会直接抛 `InvalidDefinitionException`，
  表现为“工具结果序列化失败”这种与真实原因无关的错误。
- **凡在调用线程上依赖主体上下文的入口，都要显式绑定与恢复。** 多租户插件取不到主体时
  不追加 `user_id` 条件，带空主体查询会静默读到全部用户的数据而不是报错。
  MCP 走 SDK 的处理线程，与进入 Servlet 的线程不同，必须每调用绑定一次（`McpInvocation`）。
- **MCP 的模板资源要展开成具体 URI 注册。** SDK 的 `resources/read` 只按已注册资源的 URI
  匹配，`resources/templates/list` 的清单是纯展示，只登记清单会让客户端能列出却读不到。

## 测试

- 改动后必须跑 `mvn clean test` 并确认通过；涉及 DDL / 检索 / 锁行为时还要带真实 MySQL 参数跑
  （命令见根 `README.md`），H2 不覆盖 JSON、生成列、多值索引、全文检索与锁语义。
- 权限、幂等、状态机这类规则要有单元级断言；SQL 层的行为要靠 `MysqlDdlMigrationTest`
  或真实库端到端验证，不能用 mock 代替。
- 只在真实调用才暴露的问题，要补一个不需要数据库的静态断言把它锁住
  （先例：`MemoryMapperSqlTest` 断言注解里的 SQL 字面量）。

## 编码与格式

- 源文件统一 UTF-8 无 BOM + LF，`.gitattributes` 已固定 `* text=auto eol=lf`。
  用 PowerShell 批量改写文件时注意它会写回 CRLF，改完必须复查。
- 中文注释解释“为什么”而不是“做了什么”，并写清违反时的故障表现；
  面向用户的错误提示用中文并带字段名。
- 每个业务领域一个包，内部按 application / controller / domain / infrastructure 分层；
  跨领域只通过对方的 application 服务或领域类型交互，不直连对方的 mapper。
- 基础设施类的跨领域契约放在基础设施模块内（先例：任务 payload 字段名放
  `task/domain/TaskPayload`），避免采集侧反向依赖业务模块。

## 安全边界

- 密钥、口令、Token 不入库、不入仓、不写日志；只从环境变量注入。
  开发库账号在 `application.yml` 里留了默认值，上生产前需清空改为纯环境变量。
- L3（凭证类）命中即拒绝并整体脱敏：正文换占位串，哈希 / 摘要 / 证据摘录一律不落，
  避免“换个地方存秘密”。
- 破坏性操作（`git reset --hard`、强制推送、删库删表）执行前必须先询问。

## 沟通

- 用中文回复，结论先行；改代码前先说明意图，多方案时列取舍；
  不确定的直说“需要确认”，不编造。
- 先出设计方案再实现；功能改动若会影响原有行为，必须显式告知，不能按默认悄悄改。
