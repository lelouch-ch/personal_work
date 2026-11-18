# Personal Work Twin (PWT)

个人工作记忆与进化系统。设计基线见 `Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md`（MySQL 版），
v1.0 为 PostgreSQL 历史版本。

当前进度：**M1 身份体系 + M2 Profile/Resume/Project + M3 Work Log + M4 Memory Engine +
M5 Context Builder 已完成**
（Spring Boot 3.2.2 + MyBatis-Plus + MySQL + Flyway），已在 `192.168.124.108`（MySQL 9.3.0）
完成建库、迁移与端到端验证。

测试：`mvn clean test` 287 个用例全绿（含指定数据库后端时的 18 个 DDL 用例，见「构建与测试」）。

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

密钥禁止写入 Git（设计文档 18.2）。生产环境应把 `PWT_DB_PASSWORD` 等改为环境变量注入而不依赖默认值。

未配置 `PWT_LLM_ENDPOINT` / `PWT_LLM_API_KEY` 时，Provider 退化为“未配置”实现：
REST、审核、幂等等链路照常可用，但 `EXTRACT_MEMORY` 任务会明确失败并重试，
不会伪造“提取到 0 条候选”的正常结论（ADR-025）。

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
```

步骤 1–10 已全部实测通过（见「已验证」）。PowerShell 传 JSON 请求体时建议写入文件后用
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
├── task          异步任务表、领取事务、Worker 与租约回收
├── llm           LLM Provider 抽象、OpenAI 兼容实现、提示词模板
├── publicweb     匿名公开入口
└── audit         操作审计
```

`task` 与 `llm` 不在设计文档 7 的模块表内：任务队列是跨领域基础设施，
把 payload 字段契约放在 `task/domain/TaskPayload` 后，采集侧只依赖 `task`，
不需要反向依赖 `memory`。

`context` 对应设计文档 7 的 context 模块。约束表
（`pt_constraint`）与风格规则表（`pt_style_rule`）的实体暂放本模块：MVP 只有 Context Builder
读取它们，等 M7 有维护接口时再归入各自的模块。

M6+ 的 `skill`、`report`、`mcp` 按设计文档 7 继续补充。

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
  前几段先扣，保证预算再紧也不会把用户红线挤掉。
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
- `build_context` 的 `skills` 段恒为空数组：Skill 的 CRUD 与检索在 M7，届时接入本模块的注入位。
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
