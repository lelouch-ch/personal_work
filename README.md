# Personal Work Twin (PWT)

个人工作记忆与进化系统。设计基线见 `Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md`（MySQL 版），
v1.0 为 PostgreSQL 历史版本。

当前进度：**M1 身份体系 + M2 Profile/Resume/Project + M3 Work Log 已完成**
（Spring Boot 3.2.2 + MyBatis-Plus + MySQL + Flyway），已在 `192.168.124.108`（MySQL 9.3.0）
完成建库、迁移与端到端验证。

测试：`mvn clean test` 97 个用例全绿（含指定数据库后端时的 14 个 DDL 用例，见「构建与测试」）。

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 已验证 `C:\Users\UNIS\.jdks\ms-17.0.21.0.2` |
| Maven | 3.9.x | 使用 `conf/settings-ali.xml`，本地仓库 `D:\java\maven\mavenHome` |
| MySQL | 8.0.32+ | 需 `utf8mb4` 与 `ngram` 解析器，参数要求见设计文档 8.5；已在 9.3.0 实测通过 |

## 环境变量

| 变量 | 默认值 | 必需 | 说明 |
|---|---|---|---|
| `PWT_DB_HOST` | `192.168.124.108` | 否 | 数据库主机 |
| `PWT_DB_PORT` | `3306` | 否 | 数据库端口 |
| `PWT_DB_NAME` | `personal_test` | 否 | 库名，需提前创建 |
| `PWT_DB_USER` | `root` | 否 | 数据库账号 |
| `PWT_DB_PASSWORD` | `zghz@123` | 否 | 数据库口令 |
| `PWT_OWNER_JWT_SECRET` | 空 | **是** | Owner JWT HS256 密钥，≥32 字符，缺失则启动失败 |
| `PWT_BOOTSTRAP_PASSWORD` | 空 | 首次启动需要 | 空库时创建的 Owner 初始口令 |
| `PWT_AGENT_TOKEN_PEPPER` | 空 | 否 | Agent 密钥摘要的服务端 pepper |

密钥禁止写入 Git（设计文档 18.2）。生产环境应把 `PWT_DB_PASSWORD` 等改为环境变量注入而不依赖默认值。

> 当前仓库的 `application.yml` 保留了本机开发用的库账号默认值，便于本地直接启动；
> 上生产前需清空这些默认值，改为纯环境变量注入。

## 构建与测试

```powershell
$env:JAVA_HOME = 'C:\Users\UNIS\.jdks\ms-17.0.21.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$MVN = 'D:\java\apache-maven-3.9.5-bin\apache-maven-3.9.5\conf\settings-ali.xml'

mvn -s $MVN clean test          # 单元与契约测试；DDL 冒烟测试此时跳过
mvn -s $MVN -DskipTests package
```

中文日志的编码已在工程内固化，无需再设 `MAVEN_OPTS`：`.mvn/jvm.config` 把 Maven 进程固定为 UTF-8，
`pom.xml` 的 surefire `argLine` 把测试子进程同样固定为 UTF-8。二者缺一不可——本机 Java 默认
`file.encoding=GBK`（活动代码页 936），只设其一时测试输出仍会被另一侧重新编码成乱码。

### DDL 冒烟测试（需真实 MySQL）

`MysqlDdlMigrationTest` 会验证 ngram 中文检索与全文检索模式、STORED 生成列唯一约束、多值索引、
CHECK 约束、外键与 `FOR UPDATE SKIP LOCKED`。它在独立临时库 `pwt_ddl_test` 中运行，结束后自动删除，
不会触碰业务库。两种后端任选其一：

```powershell
# 方式一：外部实例（本机无 Docker 时使用）
mvn -s $MVN test '-Dtest=MysqlDdlMigrationTest' `
  '-Dpwt.test.db.url=jdbc:mysql://192.168.124.108:3306/' `
  '-Dpwt.test.db.username=root' '-Dpwt.test.db.password=zghz@123' `
  -DfailIfNoSpecifiedTests=false

# 方式二：Testcontainers（需要 Docker，镜像 mysql:8.0.36，自动带 ngram 启动参数）
mvn -s $MVN test '-Dtest=MysqlDdlMigrationTest' -DfailIfNoSpecifiedTests=false
```

两者都不可用时该测试类按假设跳过，不会让构建失败。

> 外部实例模式下 `ngram_token_size` 属于服务器只读参数，无法由测试设置；
> 若实例值不是 2，相关用例会跳过而非失败，需按设计文档 8.5 修正部署基线。

## 启动

```powershell
$env:PWT_OWNER_JWT_SECRET   = '<至少 32 字符的随机串>'
$env:PWT_BOOTSTRAP_PASSWORD = '<初始 Owner 口令>'
java -jar target\pwt-server-0.1.0-SNAPSHOT.jar
```

启动时 Flyway 自动执行 `src/main/resources/db/migration` 下的 8 个迁移；用户表为空且配置了
`PWT_BOOTSTRAP_PASSWORD` 时创建初始 Owner（默认登录名 `owner`）。

注意 jar 会锁文件：重新 `mvn clean package` 前需先停掉正在运行的实例，否则 clean 报
`Failed to delete ...jar`。

## 接口速览

```powershell
# 1. Owner 登录，拿 JWT
$jwt = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType 'application/json' -Body '{"username":"owner","password":"***"}').data.accessToken

# 2. 校验身份
Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/whoami `
  -Headers @{ Authorization = "Bearer $jwt" }

# 3. 为 Agent 签发 API Key（仅此一次返回明文）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/agent-clients `
  -Headers @{ Authorization = "Bearer $jwt" } -ContentType 'application/json' `
  -Body '{"clientName":"codex","scopes":["work:write","memory:read"]}'

# 4. 用 Agent Key 访问，越权 scope 返回 SCOPE_REQUIRED
$agent = '<上一步返回的 accessToken>'
Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/whoami `
  -Headers @{ Authorization = "Bearer $agent" }

# 5. 撤销后立即失效
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/agent-clients/<clientId>/revoke `
  -Headers @{ Authorization = "Bearer $jwt" }

# 6. 写工作记录（Agent 用 work:write 的 Key，同一 Idempotency-Key 重复提交不重复入库）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/work-logs `
  -Headers @{ Authorization = "Bearer $agent"; 'Idempotency-Key' = 'uuid-or-random' } `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"title":"完成上传链路改造","occurredAt":"2026-11-13T09:30:00+08:00","workType":"development","tags":["ASR"],"visibility":"agent"}'

# 7. 读取与检索
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/work-logs?size=20' -Headers @{ Authorization = "Bearer $jwt" }
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/work-logs/search/by-tag?tag=asr' -Headers @{ Authorization = "Bearer $jwt" }
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/work-logs/search/by-keyword?keyword=上传' -Headers @{ Authorization = "Bearer $jwt" }
```

步骤 1–7 已全部实测通过（见「已验证」）。PowerShell 传 JSON 请求体时建议写入文件后用
`--data-binary "@file"` 或 `[Text.Encoding]::UTF8.GetBytes($json)`，避免编码与转义导致
400 请求体格式错误：`powershell.exe`（5.1）按 ANSI 读取无 BOM 的 UTF-8 脚本，脚本里的中文
请求体会在发送前就被破坏。

## 工程结构

```text
src/main/java/com/example/pwt
├── app           启动类、装配、引导、Web 拦截器
├── common        统一响应、错误码、异常处理、UUIDv7 与凭证工具
├── identity      用户、Agent Client、Token、scope、主体上下文
├── profile       个人画像、简历版本与公开链接
├── work          项目、工作记录（录入 / 分页 / 详情 / 标签 / 全文检索 / 归档）
├── publicweb     匿名公开入口
└── audit         操作审计
```

M4+ 的 `memory`、`skill`、`context`、`report`、`mcp` 按设计文档 7 继续补充。

## M1 已落实的设计约束

- user_id 由凭证解析，不接受客户端传入（设计文档 14.3）。
- MySQL 无 RLS，用 MyBatis-Plus `TenantLineInnerInterceptor` 以 `user_id` 为租户列自动追加条件，
  免拦截白名单为 `pt_user`、`pt_audit_log`、`pt_context_access_log`。
- Owner 走 JWT，Agent 走随机 API Key + SHA-256 摘要查库，两套权限判定不混用（设计文档 14.2）。
- Agent 密钥只存摘要，明文仅创建响应返回一次；撤销即时生效。
- 主键由应用生成 UUIDv7；时间统一 `datetime(3)` + UTC，连接串强制会话时区（设计文档 8.1、ADR-019）。
- 跨用户资源访问统一返回 `RESOURCE_NOT_FOUND`，不泄露资源存在性。

## M2 已落实的设计约束

- 简历版本留存：`user_id + version` 唯一；“每用户仅一份当前简历”由 STORED 生成列唯一索引保证，
  切换当前版本在同一事务内先清旧标记再置新标记。首份简历自动成为当前版本。
- 可见性判定集中在 `identity/domain/AccessPolicy.java`，固化为严格递增三档（ADR-023）：
  `private` 仅 Owner；`agent` 为 Owner + 具备 scope 的 Agent；`public` 含匿名。
  `private` 对 Agent 也不可见，否则 `agent` 档没有存在必要。
- 仅 Owner 可把内容设为 `public`，Agent 直接发布公开内容返回 400（设计文档 9.6）。
- 匿名入口 `/public/{userId}/{profile|resume|exists}` 不带认证上下文，多租户拦截器此时不加
  `user_id` 条件，因此查询必须同时显式限定 `user_id` 与 `visibility = 'public'`。
- 分页统一走 `PageQuery`：page 从 1 起、size 上限 100，越界直接 400 而不是静默截断。

## M3 已落实的设计约束

- 工作记录幂等：`Idempotency-Key` 落在 STORED 生成列 `idempotency_scope`（`user_id#key`）的唯一索引上，
  先查后插 + 捕获 `DuplicateKeyException` 回读，并发重复提交返回既有记录而不是报错（设计文档 17.3）。
- 标签写入与查询统一小写归一（`WorkLogService#normalizeTags`）：多值索引按二进制比较、大小写敏感，
  不归一会造成静默漏检（设计文档 8.4 第 9 条）。
- 关键词检索用 `MATCH ... AGAINST(... IN NATURAL LANGUAGE MODE)`，不用布尔模式：布尔模式把用户输入里的
  `( ) + - "` 当运算符，未闭合括号会静默返回 0 行（设计文档 8.4 第 15 条，实测见 DDL 回归用例）。
  布尔模式只留给 Context Builder 组装受控检索串（M5）。
- 单字词（如“锁”）在 `ngram_token_size = 2` 下无法命中全文索引，`LIKE` 兜底路径随 M5 的 Context Builder
  一并实现。
- `source_type` 由凭证判定而不是采信客户端：Owner 会话写 `manual`，Agent 会话写 `agent` 并记录
  `agent_client_id`（设计文档第 2 章「保留来源元数据」）。
- 枚举取值在送入数据库前校验，非法值返回 `VALIDATION_ERROR` 并带中文字段名，
  不让 `IllegalArgumentException` 的英文枚举名外泄。
- 新建与更新的 `updated_at` 由应用层显式赋值：MyBatis-Plus 会把读到的旧值写回该列，
  依赖 `ON UPDATE CURRENT_TIMESTAMP(3)` 会让它停在建行时刻（设计文档 8.4 第 5 条）。
- 响应字段 `memoryExtraction` 当前固定 `notQueued`：提取任务表与 Worker 属 M4，
  不提前假称 `queued`（设计文档 10.3 的示例值在 M4 后兑现）。

## 已验证

- 应用可正常启动；未配置 `PWT_OWNER_JWT_SECRET` 时启动失败并给出明确原因（fail-fast）。
- 统一响应结构生效：`{"code","message","requestId","details"}`，`X-Request-Id` 响应头回显，
  客户端传入的 `X-Request-Id` 会被复用。
- 错误码映射生效：缺凭证与非法令牌 401 `UNAUTHENTICATED`；字段校验 400 `VALIDATION_ERROR`
  并带字段级 `details`；请求体 JSON 不可解析 400（不是 500）。
- Owner JWT 与 Agent API Key 按前缀分流到两条独立鉴权路径，互不混用。
- Flyway 8 个迁移在 MySQL 9.3.0 上执行成功，`personal_test` 生成 17 张业务表 + 历史表。
- 空库引导创建 Owner，登录后返回 JWT（`expiresAt` 为 12 小时后）。
- 端到端链路：登录 → 签发 Agent Key（201，明文仅返回一次）→ 用 Agent Key 访问 `whoami`
  返回 `actorType=AGENT` 及其 scopes → 撤销 → 再用同一 Key 访问返回 401。全流程 833ms。
- `user_id` 隔离在真实库生效：库内 `pt_agent_client` 有 2 个用户的记录时，
  `GET /api/v1/agent-clients` 只返回当前用户的 1 条。
- 审计落库：`pt_audit_log` 记录 `user.register`、`user.login`、`agent_client.create`、
  `agent_client.revoke`。
- `MysqlDdlMigrationTest` 的 14 个用例在真实 MySQL 上全部通过（临时库 `pwt_ddl_test`，用完即删），
  其中含 M3 新增的全文检索模式回归：同一数据集下 `(上传` 在布尔模式命中 0 行、自然语言模式命中 1 行。
- M3 端到端 17 项在 `personal_test` 实测通过，要点：
  匿名读 401；Owner 建记录（`visibility=private`、`source=manual`、标签 `["ASR","  Upload ","asr"]`
  入库为 `["asr","upload"]`）；同 `Idempotency-Key` 重复 POST 返回同一 id 且标题仍为原记录；
  Agent 写入 `source=agent`；Agent 提交 `public` 返回 400；Owner 列表 2 条而 Agent 列表 0 条
  （`private` 对 Agent 不可见）；Agent 读 Owner 的 private 详情 404；标签检索 `ASR`/`Upload`
  大写查询各命中 1 条、不存在标签 0 条；关键词「上传」命中 2 条且 `(上传` 同样命中 2 条；
  Agent 关键词检索 0 条；项目过滤与时间范围过滤生效；非法 workType/UUID/page/visibility 均 400
  且提示为中文；可见性升为 `agent` 后 Agent 可读（200）；归档后状态为 `archived`。
  中文正文经 `HEX()` 与 `CHAR_LENGTH()` 核对为正确 UTF-8 存储，未出现转码损失。
- Agent scope 越权经真实写接口验证：仅授予 `memory:read` 的 Key 调 `POST /api/v1/work-logs`
  返回 403 `SCOPE_REQUIRED`（`Missing required scope: work:write`）。
- 工作记录审计落库：`work_log.create`、`work_log.visibility`、`work_log.archive` 均写入 `pt_audit_log`。

## 已知限制

- 目标实例 MySQL 9.3.0 高于 Flyway 9.22.3 官方支持上限（8.0），启动会输出
  `support has not been tested` 警告；迁移本身已验证可用，发布前需锁定版本组合。
- 实例 `innodb_ft_enable_stopword=ON`、`transaction_isolation=REPEATABLE-READ`，
  与设计文档 8.5 建议基线不一致（`ngram_token_size=2`、`binlog_format=ROW`、`utf8mb4` 已符合）。
  中文单字检索与任务并发领取的实际影响需在 M5 前确认。
- 数据库不可达时 `/actuator/health` 返回 503（DB 指示器 DOWN，耗时约 11s），
  而 `/actuator/health/liveness` 与 `/readiness` 保持 200，符合设计文档 18.3。
- `pt_audit_log.before_data / after_data` 尚未填充，写接口目前只记录动作与资源标识，
  字段级差异摘要待后续里程碑补齐。
- Agent scope 越权已有真实写接口的端到端验证（403 `SCOPE_REQUIRED`），另有单元级覆盖
  （`ActorContextTest`、`WorkLogServiceTest`）。
- `GET /api/v1/agent-clients` 目前返回全量列表（不分页）。
- 设计文档 10.2 的 `GET /api/v1/audit-logs` 尚未实现（当前只有写入侧），调用返回 404「接口不存在」，
  随 M9 的安全加固一并补齐。
- 工作记录尚无修改正文与逻辑删除接口，M3 只覆盖录入、读取、检索、可见性与归档。
