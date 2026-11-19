# Personal Work Twin (PWT)

个人工作记忆与进化系统。设计基线见 `Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md`（MySQL 版），
v1.0 为 PostgreSQL 历史版本。

当前进度：**M1 身份体系 + M2 Profile/Resume/Project + M3 Work Log + M4 Memory Engine +
M5 Context Builder + M6 MCP Server + M7 Skill 与工作风格 已完成**，M8 报告待做
（Spring Boot 3.2.2 + MyBatis-Plus + MySQL + Flyway），已在 `192.168.124.108`（MySQL 9.3.0）
完成建库、迁移与端到端验证。

测试：`mvn clean test` 361 个用例全绿（含指定数据库后端时的 20 个 DDL 用例，见「构建与测试」）。

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
| `PWT_LLM_ENDPOINT` | 空 | 否 | OpenAI 兼容网关地址；与 `PWT_LLM_API_KEY` 同时配置才启用真实调用 |
| `PWT_LLM_API_KEY` | 空 | 用 LLM 时必填 | 访问凭证，只从环境变量注入，不入库不写日志 |
| `PWT_LLM_MODEL` | `gpt-4o-mini` | 否 | 模型名 |
| `PWT_TASK_ENABLED` | `true` | 否 | 是否启动异步任务 Worker（测试与只跑 REST 的实例可关） |
| `PWT_TASK_LEASE_DURATION` | `5m` | 否 | 任务租约，必须大于单任务最长正常执行时间 |
| `PWT_MEMORY_AUTO_APPROVE` | `true` | 否 | 是否允许系统自动批准候选记忆 |
| `PWT_MEMORY_MAX_AUTO_RISK` | `L1` | 否 | 可自动批准的最高风险等级，调高即放宽 |
| `PWT_MEMORY_MIN_CONFIDENCE` | `0.85` | 否 | 自动批准要求的最低置信度 |
| `PWT_MEMORY_PREFERENCE_MIN_EVIDENCE` | `2` | 否 | 偏好/经验自动批准所需的最低证据条数 |
| `PWT_CONTEXT_TOKEN_BUDGET` | `4000` | 否 | `build_context` 未指定预算时的默认 Token 预算 |
| `PWT_CONTEXT_MAX_CANDIDATES` | `60` | 否 | 单次召回的候选上限，约束去重与打分规模 |
| `PWT_MCP_ENABLED` | `false` | 否 | 是否暴露 MCP 端点 `/mcp`（Agent 专用入口，默认关） |
| `PWT_MCP_ENDPOINT` | `/mcp` | 否 | MCP 端点路径 |
| `PWT_MCP_PUBLIC_BASE_URL` | 空 | 否 | 简历链接前缀；为空时 `get_resume_url` 返回站内相对路径 |

密钥禁止写入 Git（设计文档 18.2）。生产环境应把 `PWT_DB_PASSWORD` 等改为环境变量注入而不依赖默认值。

未配置 `PWT_LLM_ENDPOINT` / `PWT_LLM_API_KEY` 时，Provider 退化为“未配置”实现：
REST、审核、幂等等链路照常可用，但 `EXTRACT_MEMORY` 任务会明确失败并重试，
不会伪造“提取到 0 条候选”的正常结论（ADR-025）。

MCP 默认关闭：它是 Agent 专用入口，在 TLS 与限流落地前不随 REST 一起对全网暴露。
需要时设 `PWT_MCP_ENABLED=true`，详见「M6 已落实的设计约束」。

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

启动时 Flyway 自动执行 `src/main/resources/db/migration` 下的 10 个迁移；用户表为空且配置了
`PWT_BOOTSTRAP_PASSWORD` 时创建初始 Owner（默认登录名 `owner`）。

接入 LLM 时额外设置 `PWT_LLM_ENDPOINT` 与 `PWT_LLM_API_KEY`，Worker 即自动完成
「工作记录 → 候选记忆 → 审核」的提取；不设置则相关任务失败重试，其余功能不受影响。

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
  -Body '{"clientName":"codex","scopes":["work:write","memory:read","memory:propose"]}'

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

# 8. 提交记忆候选，再由 Owner 审核（Agent 不能自批，审核动作只有 Owner 可调）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/memories `
  -Headers @{ Authorization = "Bearer $agent" } -ContentType 'application/json; charset=utf-8' `
  -Body '{"memoryType":"experience","content":"上传 MultipartFile 要用 RequestPart","evidence":"必须用 RequestPart","confidence":0.9}'
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/memories/pending' -Headers @{ Authorization = "Bearer $jwt" }
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/memories/<memoryId>/approve" `
  -Headers @{ Authorization = "Bearer $jwt" } -ContentType 'application/json' -Body '{"comment":"已核实"}'

# 9. 检索已批准记忆（search_memories）：返回 recall_path 说明走的是全文还是 LIKE 兜底
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/context/memories/search?q=上传' `
  -Headers @{ Authorization = "Bearer $agent" }

# 10. 为当前任务构建上下文包（build_context，需 context:build）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/context/build `
  -Headers @{ Authorization = "Bearer $agent" } -ContentType 'application/json; charset=utf-8' `
  -Body '{"task":"给上传接口加文件参数校验","tokenBudget":2000,"includeTypes":["experience","fact"]}'

# 11. MCP（Agent 专用入口，需 PWT_MCP_ENABLED=true 或 --pwt.mcp.enabled=true）
# 同一张 Agent Key 直接用在 MCP 上：initialize → notifications/initialized → tools/call
$init = @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
    protocolVersion = '2025-06-18'; capabilities = @{}
    clientInfo = @{ name = 'my-agent'; version = '1.0' } } } | ConvertTo-Json -Depth 8 -Compress
$r = Invoke-WebRequest -Method Post -Uri http://localhost:8080/mcp `
  -Headers @{ Authorization = "Bearer $agent"; Accept = 'application/json, text/event-stream' } `
  -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($init))
$sid = $r.Headers['mcp-session-id']   # 后续请求必须带上这个会话头
# 工具调用（响应是 SSE 帧，业务结果在 data: 行的 text content 里）
```

MCP 的报文较啰嗦，实际接入建议直接用 MCP 客户端 SDK。手工验证时用
`[Text.Encoding]::UTF8.GetBytes($json)` 传体，并按 SSE 逐行取 `data:`；
`resources/read` 失败时 MCP SDK 0.11.3 会返回空响应体（见「已知限制」）。

M7 新增的 Skill 与风格规则（Owner 侧维护，Agent 侧只读）：

```powershell
# 12. 建 Skill（草稿）→ 发布；已发布内容再改会返回 version+1 的新草稿，归档后不再参与检索
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/skills `
  -Headers @{ Authorization = "Bearer $jwt" } -ContentType 'application/json; charset=utf-8' `
  -Body '{"slug":"code-review","name":"代码评审清单","description":"接口改动逐项核对","contentMd":"## 步骤\n1. 错误码是否复用既有枚举","visibility":"agent"}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/skills/<skillId>/publish" `
  -Headers @{ Authorization = "Bearer $jwt" }

# 13. 风格规则：新建后是 candidate，批准才会进入上下文
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/style-rules `
  -Headers @{ Authorization = "Bearer $jwt" } -ContentType 'application/json; charset=utf-8' `
  -Body '{"ruleKey":"answer_format","ruleValue":"先给结论再给理由","confidence":0.8,"visibility":"agent"}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/style-rules/<ruleId>/approve" `
  -Headers @{ Authorization = "Bearer $jwt" }
```

Agent 侧读 Skill 不走 REST：`get_skills` 工具与 `pwt://skills/list` 资源都需要 `skill:read`，
后者只给摘要（不含 `content_md` 正文），要正文按 ID 调 `get_skills`。

步骤 1–13 对应的接口能力已全部实测通过（见「已验证」）。PowerShell 传 JSON 请求体时建议写入文件后用
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
├── memory        记忆候选、敏感检测、去重冲突、风险分级、审核与版本
├── context       Context Builder：检索串组装、加权评分、去相似、Token 预算与访问审计
├── skill         Skill（工作方法）：草稿 / 发布 / 归档、版本、全文召回与 LIKE 兜底
├── task          异步任务表、领取事务、Worker 与租约回收
├── llm           LLM Provider 抽象、OpenAI 兼容实现、提示词模板
├── publicweb     匿名公开入口
├── audit         操作审计
└── mcp           Agent 接入层：Streamable HTTP 传输、认证过滤器、Tools 与 Resources
```

`task` 与 `llm` 不在设计文档 7 的模块表内：任务队列是跨领域基础设施，
把 payload 字段契约放在 `task/domain/TaskPayload` 后，采集侧只依赖 `task`，
不需要反向依赖 `memory`。

`context` 对应设计文档 7 的 context 模块。约束表
（`pt_constraint`）与风格规则表（`pt_style_rule`）的实体与读取方都在本模块：M7 的风格规则
维护接口（`StyleRuleService` / `StyleRuleController`）因此也留在 `context`——设计文档 7 的模块表
里没有独立 style 模块，为它新建包只会让同一张表的代码分裂在两个模块里。

`mcp` 是设计文档 7 的 mcp 模块，只做协议接入与权限透传，业务一律调用各领域服务，
不在本模块写查询。`report` 属 M8，尚未建立。

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
- 响应字段 `memoryExtraction` 在 M3 阶段固定返回 `notQueued`，不提前假称 `queued`；
  M4 起改为回查 `pt_async_task` 的真实进度（见下方 M4 章节）。

## M4 已落实的设计约束

- **提取闭环**：创建工作记录时在同一事务内排队 `EXTRACT_MEMORY` 任务（设计文档 6.1 的 B→C 步、
  验收用例 3）。同事务保证业务回滚不留悬空任务；任务按工作记录 ID 去重（V9 的 `dedup_key`），
  重复提交不会排队两次。
- `memoryExtraction` 回查任务表得到真实进度：`queued` / `running` / `succeeded` / `failed` / `notQueued`。
  幂等命中时同样回查——任务早已排队，若照报 `notQueued` 就是假信息。
  列表接口不逐行回查（一页会产生同等数量额外查询），直接省略该字段而不是返回未解析的固定值。
- 任务领取按 17.2 拆为同一短事务内的两步（`FOR UPDATE SKIP LOCKED` 选取 + 按 ID 置 `running`），
  耗时处理在事务提交之后；成功/失败各自开新事务回写。崩溃恢复靠租约回收，重试耗尽直接判 `failed`。
- **风险与处置一律由服务端计算**：模型或调用方自报的置信度/类型只作输入，
  敏感检测（`SensitiveFinder`）与自动批准规则（`ApprovalPolicy`）在服务端执行（设计文档 6.2）。
  阈值走配置项而非硬编码（6.3）。
- **L3 命中即拒绝并脱敏**：正文换成占位串，`normalized_hash`、`summary`、证据摘录一律不落，
  避免“换个地方存秘密”；低熵口令的摘要还可被字典碰撞反推（设计文档 15.1）。
- 提取任务送模型前先做一次敏感检测，命中 L3 的工作记录不发给外部服务；模型返回后再检测一次
  （12.3）。检测只给类别与风险，`Finding.toString()` 特意不回显原文。
- 去重不新建重复行：同用户同类型同规范化内容只做证据累加，再按累加后的条数重新判定
  （6.3 第 3 条）。已批准的重复记忆只累加证据，不改状态。
- 单次提交最多算一条证据：来源工作记录与随附摘录指向同一事实，不能各计一次，
  否则单条记录就能凑满“多次证据”，让偏好/经验门槛形同虚设（5.2）。
- 冲突检测用可解释的确定性规则（相反极性、同题异值），不依赖模型，误判交人工审核兜底。
- 修正已批准记忆不原地覆盖，而是创建新版本并置旧版 `superseded`（6.5 版本策略、场景 C）。
- 审核动作（批准/拒绝/归档/修正/替代）与待审核队列只允许 Owner，Agent 一律 403（19.2 用例 5）。
  候选统一走 `MemoryService#propose` 一条通道，Agent 提交与手动新建共用同一套规则。
- 提取留痕（模型、提示词版本、provider）落在 `pt_memory_evidence.source_metadata`：
  `pt_memory` 表没有对应列，与证据同源也更便于核对（6.6、12.3）。
- 系统主体（Worker）按 Owner 同档读取、但不得对外发布：必须能读 `private` 工作记录才能闭环，
  隔离由 `user_id` 承担；`public` 赋值权仍只属于 Owner（ADR-024）。
- **注解 SQL 不继承实体 `autoResultMap`**：JSON 列必须在 `@Results` 里显式绑定
  `JacksonTypeHandler`，否则被读成 `null`。本轮据此修复两处静默缺陷，见设计文档 8.4 第 18 条。
- **租户插件会破坏 `FOR UPDATE SKIP LOCKED`**：领取与租约回收语句必须
  `@InterceptorIgnore(tenantLine = "true")`，详见设计文档 8.4 第 17 条。

## M5 已落实的设计约束

- **布尔模式只用于自行组装的检索串**（11.3 第 3 步、8.4 第 15 条）：`ContextQuery` 先剔除运算符，
  再按文字种类把词元切开（`使用feign上传` → `feign` + 中文段），中文段滑动展开为 2-gram。
  整句直送布尔模式等于要求原文出现完全连续的同一段文字，换个说法召回就是 0。
- **单字查询走 `LIKE` 兜底**：`ngram_token_size = 2` 下单字永远命中不了索引，
  布尔检索空转与“确实没有相关记忆”不可区分，因此空结果必须补一次子串匹配，
  并在响应的 `context_meta.recall_path` / `recall_path` 里标明走了哪条路径。
- **评分按 11.4 的加权求和**，相关度分项取 `MATCH` 分数按候选集最大值归一化，
  `LIKE` 兜底命中记固定低分；新近度与使用信号用半衰期衰减而不是线性截断，
  避免阈值附近排序抖动。权重固化在 `ContextWeights`，可调旋钮（候选数、类型配额）走配置。
- **约束是硬规则而不是排序项**（11.5）：预算按 画像/项目 → 约束 → 风格 → 相关记忆 自上而下分配，
  前几段先扣，保证预算再紧也不会把用户红线挤掉（M7 起 Skill 段再排在相关记忆之后）。
- **类型配额防止一类记忆吃满预算**：经验类条数最多、句子最长，纯按分数填充会把偏好与决策挤光，
  因此 `pwt.context.type-quotas` 给每类封顶，被挤掉的条数在 `dropped_by_quota` 里报出。
- **最高分条目超预算时不整段清空**：跳过它继续装下一条。端到端实测到旧写法在预算 500 时
  注入 0 条（383 token 白留着），修正后同场景注入 5 条。
- **去相似只在服务层做**：`normalized_hash` 只判完全相同，`MATCH` 分数只反映与查询串的关系，
  两者都不能发现“同一件事换一种说法”。用字符 2-gram 的 Jaccard（阈值 0.85，刻意取高：
  误判合并会让一条记忆静默消失，代价高于多留一条冗余）。
- **`build_context` 只返回 approved**，且过滤过期与不在有效期内的记忆（9.7）；
  candidate / rejected / archived / superseded 一律不进上下文。
- **画像按 `profile:read` 判定，而不是随 `context:build` 一并放行**：只授予 `context:build` 的
  Agent 不应通过上下文包旁路读到 private 画像（14.2）。
- **按项目过滤时保留“项目未知”的记忆**（8.4 第 21 条）：全局偏好与约束通常没有项目归属，
  排除它们会让每次带项目的提问都丢掉用户风格；归属明确但属他项目才排除。
  `pt_memory.project_id`（V10）由来源工作记录在 `insert` 之前带入——落库后再改内存对象不会回写该列。
- **`last_used_at` 用一条批量 UPDATE 回写**，且在业务事务之外、失败只记日志（11.4 禁止主事务逐行写）。
- **边界标记随结果返回而不强行包裹正文**（15.2）：服务端强行包裹会破坏 JSON 结构稳定性，
  标记交由调用方拼接提示词时使用；`includeSkills=false` 时连标记一并省略。

## M6 已落实的设计约束

- **传输用 Streamable HTTP 而非 SSE-only**（9.1、ADR-006）：单个端点 `/mcp` 同时支持普通
  POST 请求与 SSE 流式响应，客户端不必维持两条连接。传输提供者本身是 `HttpServlet`，
  必须用 `ServletRegistrationBean` 显式挂到配置路径——只声明成普通 Bean 时 Boot 会把它
  兜底注册到 `/`，等于整站路径都交给 MCP 处理。
- **认证用 Filter 而不是 MVC 拦截器**：拦截器只挂在 MVC 的 `HandlerMapping` 上，对独立注册的
  Servlet 完全不生效，漏了这层就是 MCP 全裸。凭证解析抽成 `identity/application/CredentialResolver`
  与 REST 共用，避免“同一个密钥一边能用一边被拒”的规则漂移。
- **主体跨线程用 SDK 的 `contextExtractor` 传递**，并在每次调用开始时绑定 `ActorContextHolder`：
  MCP 的处理线程与进入 Servlet 的线程不同，而本项目的多租户拦截器在取不到主体时**不追加**
  `user_id` 条件，一旦带着空主体查询就会静默读到全部用户的数据。因此取不到主体直接失败，不放行。
- **输出是 9.5 的统一包装放在 text content 里，不给 `structuredContent`**：SDK 在工具声明了
  `outputSchema` 时会按该 schema 校验 `structuredContent`，而包装里的 `meta` 每次都变，
  严格对齐等于给每个工具再写一份包装 schema。9.5 本身写的是“建议”，同时满足 9.1
  “提供简短 text content 以兼容客户端”。
- **业务失败不是协议错误**：scope 不足、参数非法转成 `isError=true` + 错误码 + 中文原因，
  让 Agent 有机会自我修正；只有非预期异常才走 JSON-RPC 层，且只回通用提示，堆栈留在服务端。
- **MVP 只注册 6 个工具**：`get_profile`、`get_resume_url`、`capture_work_log`、`propose_memory`、
  `search_memories`、`build_context`（M7 起补上 `get_skills`，共 7 个）。报告类工具属 M8，
  与其注册一个恒返回空的占位工具，不如让它在 `tools/list` 里干脆不出现。
- **写工具不给 `public` 可见性**（9.6）：Schema 的 enum 里就只有 `private` / `agent`，
  否则客户端要先猜、再被服务端拒一次。
- **入参用 snake_case，输出复用 REST 的 DTO**：入参是 MCP 约定，输出与 REST 保持一致，
  Agent 与前端读到的是同一份字段语义。所有解析错误都点名到具体字段。
- **模板资源必须展开成具体 URI 注册**（9.2）：SDK 的 `resources/read` 只匹配**已注册资源**的
  URI 模板，`resources/templates/list` 的清单只用于展示。因此 `pwt://memory/approved/{memory_type}`
  按六种记忆类型逐个注册，非法类型自然落到“资源不存在”，而不是静默返回空列表。
- **资源覆盖 9.2 表的全部 URI**：六个固定资源（`profile/identity`、`profile/resume`、
  `profile/style`、`profile/constraints`、`memory/approved`、`work/recent`）加一个按类型模板，
  M7 补上 `pwt://skills/list`，合计 13 个可读资源。可见性一律交给服务层判定，MCP 侧不另写一套。
- **默认关闭**（`pwt.mcp.enabled=false`）：未做 TLS 与限流前不随 REST 一起对外暴露，
  关闭时 `/mcp` 返回 404，REST 与契约测试均不受影响。

## M7 已落实的设计约束

- **不需要新迁移**：`pt_skill`（V5）与 `pt_style_rule`（V6）在 M1 建库时就已就位，M7 补的是
  写入方与读取方。索引层面只做了核对：`ft_skill_text` 确认覆盖 `name`、`description`、
  `content_md` 三列，只检索正文会让「按名称找流程」这种最常见的用法落空。
- **已发布内容不可就地改写**（15.2「Skill 发布前由 Owner 审核」）：草稿原地改；
  对已发布的 Skill 调 `PUT` 会新建 `version + 1` 的草稿并原样返回它，原已发布行不动。
  否则 Agent 正在用的流程会在两次调用之间被换掉，那次改写实际绕过了发布这道审核。
- **发布即切换**：`publish` 在同一事务内把同 slug 的其它已发布版本转 `archived`，
  保证同一 slug 最多一条生效，避免同一流程的两个版本同时注入上下文。
- **归档不能直接发布**：归档表达「这套做法不再适用」，重新启用要显式新建版本，
  避免一次误点让过时流程回到 Agent 上下文。归档与重复发布都是幂等或明确报错，不静默通过。
- **版本号取历史最大值 + 1**，不过滤状态：草稿、已发布、归档都占着
  `uq_skill_user_slug_version` 的位置，从最大值往后分配才不会再撞唯一键。
- **slug 必须由 Owner 显式给出**（`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`）：名称允许中文，
  按 ASCII 自动推导的结果往往只剩一串连字符，既不稳定也不可辨识，不如直接报错让 Owner 起一个。
  大小写与连续连字符归一（`Hand--Off` → `hand-off`），避免同一流程出现两个 slug。
- **写入侧只有 Owner**（10.2）：`POST` / `PUT` / `publish` / `archive` 对 Agent 一律 403，
  Agent 经 MCP 读（`get_skills` + `pwt://skills/list`，均需 `skill:read`），不能自己给自己加流程。
  草稿对 Agent 按不存在处理（404）——拿到没审完的流程正是 15.2 要避免的事。
- **`pwt://skills/list` 只给摘要**：`content_md` 是 `mediumtext`，一条流程正文就能上万字，
  列举场景把正文全灌进响应，Agent 光传输就要花掉预算。要正文按 ID 调 `get_skills`。
- **`get_skills` 的取值校验放在服务层而不是 MCP 层**，与 `ContextSearchService.search` 同构：
  REST 与 MCP 两条入口必须得到完全一致的判定结果（9.1）。`query` 与 `skill_ids` 同时给出时
  取交集而不是忽略其一——Agent 说「上次那几篇里跟部署相关的挑出来」，忽略任一条件都是曲解它的意思；
  ID 白名单里已归档或已失效的条目静默跳过，避免跨会话的时间差把 Agent 卡在重试上。
- **召回沿用 M5 机制**：检索串由 `ContextQuery` 组装后走布尔模式，空转补 `LIKE` 兜底并记路径；
  全文索引列覆盖三列，兜底语句用 `idx_skill_user_status` 先按 `user_id + status` 收敛扫描范围。
- **Skill 注入位次排在相关记忆之后**（11.5：Skill 是「补充怎么做」，记忆是「用户是谁、要什么」）：
  按条给预算，**装不下整条就不装**并在 `truncated_sections` 标 `skills`——Markdown 流程被截成
  半截会误导 Agent 以为那就是完整步骤。单条注入文本上限 2000 字符（记忆是 600），
  条数上限 `pwt.context.max-skills`（默认 3）。
- **`context_meta.selected_count` 仍只算记忆段**，不改变 M5 语义；本次注入了哪些 Skill 由
  `skills` 数组与 `pt_context_access_log.skill_ids` 表达。`include_skills=false` 时连边界标记一并省略。
- **风格规则状态只有 `candidate → approved | archived`**：服务端不强设 `sourceCount` /
  `confidence` 阈值——6.3 的证据条数规则只针对记忆，而风格规则目前没有任何自动产出方
  （M4 的提取只产六类记忆），规则全部由 Owner 手写，再拿阈值卡自己写的规则没有意义。
  两个数值仅作 Owner 的判据展示。
- **风格规则的读取方只有 Context Builder 与 `pwt://profile/style`**，且只看 `approved`；
  每用户内 `rule_key` 唯一（`uq_style_rule_user_key`），不允许同键两行让 Builder 无所适从。

## 已验证

- 应用可正常启动；未配置 `PWT_OWNER_JWT_SECRET` 时启动失败并给出明确原因（fail-fast）。
- 统一响应结构生效：`{"code","message","requestId","details"}`，`X-Request-Id` 响应头回显，
  客户端传入的 `X-Request-Id` 会被复用。
- 错误码映射生效：缺凭证与非法令牌 401 `UNAUTHENTICATED`；字段校验 400 `VALIDATION_ERROR`
  并带字段级 `details`；请求体 JSON 不可解析 400（不是 500）。
- Owner JWT 与 Agent API Key 按前缀分流到两条独立鉴权路径，互不混用。
- Flyway 10 个迁移在 MySQL 9.3.0 上执行成功，`personal_test` 生成 17 张业务表 + 历史表。
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
- V9 迁移在 `personal_test` 上真实执行成功（`Successfully applied 1 migration, now at version v9`），
  新增的 DDL 用例验证任务去重键：NULL 不参与唯一判定、同用户同类型同对象被拒、
  换对象/换类型/换用户均可并行排队（DDL 用例总数因此为 15）。
- **M4 闭环端到端**：本机起一个 OpenAI 兼容的桩服务（`/v1/chat/completions` 固定返回 2 条候选），
  创建一条工作记录后 Worker 在轮询周期内领取、调用模型、落库，
  详情接口的 `memoryExtraction` 由 `queued` 变为 `succeeded`，任务表状态 `succeeded`、`retry_count=0`、
  `payload` 可被 `JSON_EXTRACT` 正确回读。
- 模型返回的 2 条候选按规则分流：`fact`（置信度 0.93、风险 L0）被 **AUTO_APPROVE** 直接批准；
  `experience`（单条证据）判 **PENDING_REVIEW**，原因写“经验类记忆需要至少 2 条证据，当前 1 条”，
  符合 6.3 第 2/3 条。
- 提取留痕入库可查：`extractorVersion=memory_extraction_v1`、`model=stub-model`、
  `llmProvider=openai-compatible`、来源工作记录 ID 与原文摘录均在 `pt_memory_evidence`。
- 审核权限：Agent 调 `POST /memories/{id}/approve` 返回 403；Owner 批准后状态转 `approved`，
  审核轨迹为 `AUTO:PENDING_REVIEW → OWNER:APPROVE`，两级留痕都在。
- Agent 调 `GET /memories/pending` 返回 403（10.2 将 pending 标为 Owner 专属）。
- 去重：重复提交同一内容不新增记忆行，证据数由 1 累加到 2。
- 敏感检测：Owner 提交含 `password = SuperSecret999` 的候选，返回 `status=rejected`、
  `risk=L3`，正文变为 `[已脱敏：命中凭证类敏感信息]`，`leaked=False`（原文未在响应中出现）。
- 版本管理：修正已批准记忆生成 `version=2` 且 `supersedes_id` 指向旧版，旧版状态转 `superseded`。
- Agent 提交 `visibility=public` 返回 400；记忆审计动作 `memory.propose`（system）、
  `memory.approve`、`memory.reject`、`memory.revise`、`memory.supersede` 均已落 `pt_audit_log`。
- 中文经 `HEX()`/`CHAR_LENGTH()` 复核为正确 UTF-8 存储（如“项”= `E9A1B9`）。
- 顺带修复 M3 遗留缺陷：标签检索与全文检索返回的记录 `tags` 恒为空数组
  （注解 SQL 未继承 `autoResultMap`），修复后两处均正确返回 `[feign,upload]`。
- V10 迁移在 `personal_test` 上真实执行成功（`now at version v10`），新增 3 个 DDL 用例：
  上下文日志的 `project_id` 可空、记忆的“本项目 + 项目未知”联合过滤、
  按项目召回的两条路径经 `EXPLAIN` 确认命中索引（DDL 用例总数因此为 18）。
- **M5 端到端**（`personal_test`，Worker 关闭，只走 REST）：
  带来源工作记录的记忆 `project_id` 正确继承自工作记录，人工提交的无来源记忆为 `NULL`（项目未知）；
  `search_memories` 中文短语走 `full_text`，单字「锁」降级为 `like` 且仍命中含该字的记忆；
  `build_context` 不指定项目召回 9 条、指定甲项目时恰好少 3 条（被排除的正是归属乙/丙项目的记忆），
  无项目归属的偏好与事实两个场景都保留，`project` 段返回请求项目的名称；
  `includeTypes=["preference"]` 时只返回 preference 类型；
  预算 500 时 2 条约束与 2 条风格规则全部保留、记忆段截断并标记 `truncated_sections=["memories"]`，
  `token_budget=100` 越界返回 400 且提示为中文；
  `pt_context_access_log` 记到 18 条（含 `project_id`、注入的记忆 ID 数组、预算与估算），
  `pt_memory.last_used_at` 被批量回写，`pt_audit_log` 的 `context.build` 同步落库。
- M5 顺带修复两处真实缺陷：记忆的项目归属在 `insert` 之后才赋值导致库里存 `NULL`；
  `LIKE ... ESCAPE` 的反斜杠转义少写一层，使单字兜底查询直接抛
  `Parameter index out of range`（后者已用 `MemoryMapperSqlTest` 的注解值断言锁死）。
- **M6 端到端**（真实 MySQL `personal_test` + MCP Streamable HTTP 协议往返，非 mock）：
  无凭证 `POST /mcp` → 401 且响应体是统一错误结构；`initialize` → 200 返回
  `serverInfo` / capabilities / 中文 instructions 与 `mcp-session-id`；
  `notifications/initialized` → 202；`tools/list` → 6 个工具（SSE 帧，中文描述与完整 inputSchema）；
  `tools/call` 六个工具全部 200，其中 `capture_work_log` 真实写入 `pt_work_log`
  （`source=agent`、`memoryExtraction=queued` 并随后排入 `pt_async_task`）、
  `propose_memory` 真实写入 `pt_memory`（`status=candidate`，服务端把风险重判为 `L0`）、
  `build_context` 写 `pt_context_access_log` 与 `pt_audit_log`（`context.build`）、
  `pt_agent_client.last_seen_at` 随调用更新；
  `resources/list` 返回 12 个资源（6 固定 + 6 类型展开）、`resources/templates/list` 返回 1 个模板，
  `pwt://memory/approved/experience`、`pwt://profile/style`、`pwt://work/recent` 均实测读到信封 JSON
  （`pwt://work/recent` 返回真实的最近记录，`tags` 为 `["m6","mcp"]`）；
  缺必填参数 → `isError=true` + `VALIDATION_ERROR` + 中文点名 `title`；
  未知工具 → JSON-RPC `-32603`；scope 不足的 Key 调写工具与 `build_context` 均返回
  `SCOPE_REQUIRED` 并说明缺哪个 scope，仅 `memory:propose` 的 Key 读画像资源同样被拒；
  `PWT_MCP_ENABLED=false`（默认）时 `POST /mcp` → 404，REST 接口不受影响。
- M6 顺带修复两处真实缺陷：MCP 侧的 `ObjectMapper` 是裸构造的，缺 JavaTime 模块，
  导致所有含 `Instant` 的 DTO 序列化失败（表现为“工具结果序列化失败”，已在 `McpJson` 显式注册
  `JavaTimeModule` 并与 REST 一致输出 ISO-8601）；模板资源此前只登记清单未登记可读资源，
  `pwt://memory/approved/{memory_type}` 一律报“资源不存在”，已按类型展开注册并由
  `McpResourceCatalogTest` 锁死“清单与可读性必须同时成立”。

- **M7 端到端**（真实 MySQL `personal_test` + MCP 协议往返，32 项断言全通过）：
  Owner 建 Skill 为 `draft` / `version=1`；草稿阶段 Agent 的 `get_skills` 返回 0 条、
  REST 详情 404，而 Owner 列表可见；草稿 `PUT` 原地改（id 与 version 不变）；
  `publish` 后对已发布行再 `PUT` 返回 `version=2` 的**新**草稿且原行仍为 `published`，
  对已发布行重复 `publish` 返回 400「只有草稿可以发布，当前状态为 published」；
  Agent 按 ID 读到完整正文、按中文关键词（`错误码 多租户 TypeHandler`）召回命中，
  `query` + `skill_ids` 取交集（指向草稿 ID 时为空）；
  `tools/list` 7 个工具、`resources/list` 13 个资源，`pwt://skills/list` 只返回摘要字段
  （`id,slug,name,description,version,visibility,updatedAt`，无 `contentMd`）；
  `build_context` 的 `skills` 段注入 3 条，`pt_context_access_log.skill_ids` 落库为真实 ID 数组；
  上万字的长 Skill 在 `token_budget=500` 下**整条不装**（该条不在 `skills` 里）且
  `truncated_sections=["skills"]`，而同时召回的短流程仍照常装入（估算 112 token）；
  预算 4000 时长流程装入并被截到 1902 字符（上限 2000）；
  `include_skills=false` 时 `skills` 为空且边界标记一并省略；
  Agent 调 `POST /api/v1/skills` 与 `POST /api/v1/style-rules` 均 403；
  缺 `skill:read` 的 Key 调 `get_skills` 返回 `isError=true` + `SCOPE_REQUIRED`，
  读 `pwt://skills/list` 同样拿到带 `SCOPE_REQUIRED` 的信封；
  风格规则新建为 `candidate` 且不进上下文，`approve` 后同时出现在 `pwt://profile/style`
  与 `build_context.style_rules`（`{"key":"m7_e2e_answer_format_*","value":"先给结论再给理由，代码用中文注释"}`）；
  归档后 `get_skills` 立即查不到该条。
- 索引核对：`information_schema.STATISTICS` 确认 `pt_skill` 的 `ft_skill_text` 覆盖
  `name`、`description`、`content_md` 三列；`MysqlDdlMigrationTest` 新增两个用例锁死这一点
  （中文三列分别命中；同 slug 多版本可共存而同 `(user_id, slug, version)` 被唯一键拒绝）。

## 已知限制

- 目标实例 MySQL 9.3.0 高于 Flyway 9.22.3 官方支持上限（8.0），启动会输出
  `support has not been tested` 警告；迁移本身已验证可用，发布前需锁定版本组合。
- 实例 `innodb_ft_enable_stopword=ON`、`transaction_isolation=REPEATABLE-READ`，
  与设计文档 8.5 建议基线不一致（`ngram_token_size=2`、`binlog_format=ROW`、`utf8mb4` 已符合）。
  中文单字检索已在 M5 验证：`ngram_token_size=2` 在本实例为 ON 也不影响，单字由 `LIKE` 兜底覆盖；
  任务并发领取受 `REPEATABLE-READ` 影响仍未评估（见下）。
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
- **默认预算下 Skill 很可能一条都注入不进去**：Skill 段排在相关记忆之后，
  `default-token-budget` 又是 4000，真实语料的记忆段几乎必然吃满剩余预算。
  端到端是用「上万字长 Skill + 预算 500」构造出截断路径的，还没有用真实语料测过
  「该给 Agent 的流程实际给到了」。若要让 Skill 稳定进入上下文，需要给 Skill 段留最低配额
  或允许 Agent 显式请求，二者都要先改 11.5 的位次约定。
- **`get_skills` 返回完整 `mediumtext` 正文**：一次最多 10 条（上限 50），
  若 Owner 写入过长流程，单次工具响应可能远大于 `build_context` 的预算口径——
  MCP 侧没有对正文再截断，靠 `pwt://skills/list`（摘要）缓解。
- **风格规则目前没有任何自动产出方**：M4 的 LLM 提取只产六类记忆，`pt_style_rule` 的行
  只能由 Owner 手工写入，`source_count` 恒为 1、`confidence` 由 Owner 自报。
  「从工作记录里归纳风格」还没有实现。
- Agent 侧没有 Skill 的 REST 读取入口（按 10.2 设计如此：Agent 走 MCP），
  `GET /api/v1/skills` 对 Agent 恒为 403，前端之外若要用 HTTP 读 Skill 只能走 Owner 会话。
- 相关性排序尚未做过召回质量评测：权重与配额取自设计文档 11.4/11.5 的建议值，
  只用构造数据验证过相对顺序，没有用真实语料测过“该召回的没召回”。
- Token 估算是字符分档估算（CJK 每字 1 token、ASCII 每 4 字符 1 token）而不是模型 tokenizer，
  按“宁可高估”取保守上界；接真实模型后应由 tokenizer 适配器替换。
- 相似度去重是字符 2-gram Jaccard，只判“几乎同一句话”，同义改写与跨语言重复仍会并存。
- `OpenAiCompatibleChatProvider` 只用本机桩服务验证过请求拼装与响应解析，
  **未接真实模型服务**；真实网关的鉴权、限流、超时与流式行为需在接入时复测。
- 租约回收（`recoverExpiredLeases`）只有单元级覆盖，未在真实 MySQL 上用两个并发 Worker
  验证跨实例互不重复消费；设计文档 8.5 要求的 `READ-COMMITTED` 在本实例是
  `REPEATABLE-READ`，其对任务领取的实际影响仍未评估（M5 前确认）。
- 冲突检测是 MVP 的确定性规则，不处理跨句否定与英文 `not` 作用域，
  这类情况会漏判（漏判的后果是两条记忆并存，由人工审核发现，不会误覆盖）。
- 近似去重只用了 InnoDB 全文检索 + 规则；向量相似度按 6.5 与 ADR-013 的约定留待后续外挂。
- 记忆尚无 `valid_from`/`valid_until` 的编辑入口与过期清理任务，`expires_at` 仅写入侧支持。
- 自动批准阈值是全局配置，尚无按用户/类型的差异化策略。
- **MCP SDK 0.11.3 的 servlet 传输在 handler 抛 `McpError` 时只记日志并返回空响应体**：
  例如读取未注册的 `pwt://nope/x`，客户端拿到 HTTP 200 + 空 body，而不是 JSON-RPC 的
  `-32002 resource not found`。已实测确认是上游行为（`responseStream(...).block()` 的
  catch 分支只 `AsyncContext.complete()`），不修是为了不 fork SDK；
  资源清单与模板已展开为可读 URI，正常客户端不会走到这条路径，服务端日志可见原因。
  升级 SDK 后需复测此项。
- MCP 尚无独立的接入审计表：工具调用复用领域侧的 `pt_audit_log` 与 `pt_context_access_log`，
  因此“某张 Agent Key 通过 MCP 调了哪些工具”目前只能从服务端日志追溯。
- MCP 端点除 Bearer 凭证外没有额外防护（无限流、无 TLS 终结、无 IP  allowlist），
  这是它默认关闭的直接原因；M9 生产化时补齐。
- SSE 长连接与并发只做过单连接协议验证，未压测；`mcp` 依赖被 Boot 3.2.2 的 BOM
  降级为 jackson 2.15.3 / reactor 3.6.2，兼容性只有真实协议往返的证据，没有负载证据。
