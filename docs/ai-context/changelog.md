# 变更记录

里程碑级总结，最新在前。测试数与验证结论均来自实际运行，未跑过的不写。

## 里程碑 6 - 2026-11-18 MCP Server（Agent 接入层）

- 提交：`04823ab`
- Added: `mcp` 模块——Streamable HTTP 传输挂到 `/mcp`、认证过滤器、六个 Tools、
  六个固定 Resources（含 9.2 的 `pwt://work/recent`）与按类型展开的模板资源；统一输出包装（设计文档 9.5）
- Added: `identity/application/CredentialResolver`（从 REST 拦截器抽出，与 MCP 共用凭证判定）、
  `context/application/GovernanceQueryService`（从 `ContextDataReader` 抽出，MCP 资源与
  Context Builder 共用一套查询）
- Added: 配置块 `pwt.mcp`（`enabled` 默认 false、endpoint、服务名/版本、公开链接前缀）
- Added: 生产依赖 `io.modelcontextprotocol.sdk:mcp:0.11.3`（用户批准，理由见 ADR-026）
- Changed: 业务失败与非预期失败统一置 `isError=true`，去掉 `Failure.protocolError`
  这个恒为真的字段（原设计打算区分两类失败，实现后发现协议层并无第二档可用）
- Fixed: MCP 侧自建 `ObjectMapper` 缺 JavaTime 模块，含 `Instant` 的 DTO 一律序列化失败
- Fixed: 模板资源只登记清单未登记可读资源，`pwt://memory/approved/{memory_type}` 一律“资源不存在”
- Fixed: 两处中文注释在写入时被二次编码成乱码，已重写并全仓库扫描确认无残留
- Docs: 根 `README.md` 增补 M6 章节、接口速览第 11 步、环境变量与已知限制；
  `requirements.md` 新增「Agent 接入（MCP）」；`decisions.md` 补 ADR-026 的 SDK 行为后果
- Verified: 2026-11-18 `mvn clean test`（带真实 MySQL 参数）**322 例全绿**（此前 287 例），
  新增 `McpArgsTest` / `McpInvocationTest` / `McpToolCatalogTest` / `McpResourceCatalogTest` /
  `McpWiringTest` 共 34 例，另给 `PwtApplicationContextTest` 补了「MCP 默认关闭」断言
- Verified: 真实协议往返（`personal_test` + 起真实实例）——401、握手、会话、SSE、
  六个工具（含真实写入与审计落库）、12 个资源读取、参数与 scope 错误结构、
  默认关闭时 `/mcp` 404 且 REST 不受影响
- Not verified: SSE 长连接与并发未压测；MCP 端点无限流与 TLS；SDK 抛 `McpError` 时
  返回空响应体属上游行为，未修

## 里程碑 5 - 2026-11-18 Context Builder

- 提交：`bfde327`
- Added: `context` 模块（检索串组装、召回、加权评分、去相似、Token 预算分配、访问审计）；
  接口 `POST /api/v1/context/build`、`GET /api/v1/context/memories/search`；
  `common/util/TokenEstimator`；`memory/application/MemoryRecallService`（供上下文与搜索共用）；
  配置块 `pwt.context`
- Added: V10 迁移——`pt_context_access_log.project_id`、`pt_memory.project_id` 与
  `pt_memory_evidence.work_log_id` 索引
- Changed: `MemoryResponse` 增加 `projectId` 字段（响应结构变化，向后兼容新增字段）；
  记忆的项目归属由来源工作记录在落库前带入
- Fixed: 记忆项目归属在 `insert` 之后才赋值，导致库里存 `NULL` 而接口返回带值
- Fixed: `LIKE ... ESCAPE` 反斜杠少转义一层，单字兜底查询抛 `Parameter index out of range`
- Fixed: 最高分条目超预算时整段清空——改为跳过继续填充，预算 500 场景由注入 0 条变为 5 条
- Docs: 设计文档新增 8.4 第 19/20/21 条与 8.6 的 V10 说明；根 `README.md` 增补 M5 章节
- Verified: 2026-11-18 `mvn clean test`（带真实 MySQL 参数）287 例全绿，其中
  `MysqlDdlMigrationTest` 18 例在 MySQL 9.3.0 通过；V10 在 `personal_test` 实际执行到 v10
- Verified: 端到端实测——项目归属继承与人工提交为 `NULL`；中文短语走 `full_text`、
  单字「锁」降级 `like` 仍命中；指定项目比不限项目恰好少 3 条（均为他项目记忆），
  项目未知的偏好与事实两个场景都保留；预算 500 时 2 条约束与 2 条风格规则全保留、
  记忆段标记截断；`token_budget=100` 返回 400 中文提示；`pt_context_access_log` 18 条、
  `last_used_at` 批量回写、`context.build` 审计落库
- Not verified: 召回质量未用真实语料评测（只用构造数据验证相对顺序）；
  Token 估算未对标任何真实模型 tokenizer

## 里程碑 4 - 2026-11-18 记忆引擎与异步任务框架

- 提交：`92c4846`
- Added: `task` 模块（领取事务、Worker、租约回收、退避重试）；`llm` 模块
  （Provider 抽象 + OpenAI 兼容实现 + 未配置实现 + 提示词模板）；`memory` 模块
  （提取、去重、冲突检测、审批策略、敏感检测、审核与版本替代）；V9 迁移（任务去重键）
- Fixed: 租户插件重建 SQL 使 `FOR UPDATE SKIP LOCKED` 语法非法（改用 `@InterceptorIgnore`）
- Fixed: 注解 SQL 不继承 `autoResultMap` 导致 JSON 列读成 `null`，含 M3 遗留的 `pt_work_log.tags`
- Fixed: 证据计数把一份工作记录与其摘录算两次，单条记录即可凑满“多次证据”
- Docs: ADR-024（系统主体读同 Owner、不得发布 public）、ADR-025（LLM 未配置时显式失败）；
  设计文档 8.4 第 16/17/18 条
- Verified: `mvn clean test` 209 例全绿（此前 97 例）；V9 在 `personal_test` 执行成功；
  用本机 OpenAI 兼容桩服务跑通“工作记录 → 提取任务 → 候选记忆 → Owner 审核”闭环，
  含自动批准与人工审核分流、去重累加证据、L3 脱敏、版本替代与审计留痕
- Not verified: 未接真实模型网关；租约回收未在真实库上用两个并发 Worker 验证

## 里程碑 3 - 2026-11-13 工作记录录入与检索

- 提交：`d6a436f`
- Added: 工作记录录入（幂等键走 STORED 生成列唯一索引）、分页与详情、标签检索、
  全文检索、可见性调整与归档
- Docs: 设计文档 8.4 第 15 条与 11.3 的检索模式分工；`.mvn/jvm.config` 与 surefire
  `argLine` 固化 UTF-8，修复中文测试日志乱码
- Verified: `mvn clean test` 97 例全绿（含真实 MySQL 14 个 DDL 用例）；
  `personal_test` 上 17 项端到端验证通过，含幂等重复提交、标签大小写归一、
  未闭合括号关键词仍能召回、Agent 与 Owner 可见性差异、中文按 UTF-8 正确存储

## 里程碑 2 - 2026-11-13 画像、简历版本与项目管理

- 提交：`b1a39fd`
- Added: 画像读写、简历版本留存与当前版本切换、固定公开链接、项目 CRUD 与归档、匿名公开入口
- Added: `AccessPolicy` 把可见性判定集中为三档阶梯（ADR-023）；`PageQuery` 统一分页边界
- Fixed: 路径变量类型不匹配（如非 UUID）返回 500，改为 400 `VALIDATION_ERROR`
- Verified: `mvn clean test` 71 例全绿（含真实 MySQL 13 个 DDL 用例）

## 里程碑 1 - 2026-11-13 工程骨架与身份体系

- 提交：`d17e428`
- Added: 数据库基线从 PostgreSQL 迁到 MySQL（v1.1 为当前基线，v1.0 保留为历史只读版本）；
  Flyway V1~V8 建 17 张业务表；Owner JWT 与 Agent API Key 双轨；scope 校验；
  多租户插件按 `user_id` 隔离；统一响应与错误码；`X-Request-Id` 透传；审计独立事务
- Fixed（仅在真实 MySQL 暴露）：审计与上下文访问日志去掉 `user_id` 外键，
  否则独立事务对未提交父行加共享锁，自等到 1205 超时（50s → 0.8s，记为 ADR-022）；
  多值索引不接受 `CHARACTER SET/COLLATE` 子句；承载多值索引的 JSON 列必须可空
- Verified: `mvn clean test` 全绿；`MysqlDdlMigrationTest` 13 例在真实 MySQL 通过，
  登录 → 签发 Agent Key → 使用 → 撤销的端到端全流程通过

## 里程碑 0 - 设计与初始化

- Added: 设计文档 v1.0（PostgreSQL）与 v1.1（MySQL）；仓库初始化并关联远端
- Verified: MySQL 实例连通性与建库；Flyway 空库建表到 V8

## 知识库自身

- 2026-11-18：用 ai-context 技能初始化 `docs/ai-context/` 与仓库 `AGENTS.md`。
  设计文档仍是规格与 ADR-001~025 正文的权威来源，本知识库不复述（理由见 ADR-027）。
