# 项目概览

Personal Work Twin（PWT）是个人工作记忆与进化系统：为一个人采集工作痕迹、沉淀可信的长期记忆，
并在 Agent 执行任务前，把少量相关、有效且授权可见的个人上下文交给它。
主形态是 Spring Boot 模块化单体，对外同时提供 REST（Web 管理端）与 MCP（Agent 接入层）。

规格与决策的权威来源是仓库根目录的
`Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md`（MySQL 版，含 DDL、接口契约与 25 条 ADR 正文）。
本知识库只保留“当前状态、约定与索引”，不复述规格；
`Personal_Work_Twin_完整技术设计文档_v1.0.md` 是 PostgreSQL 历史版本，只读不改。

## 当前状态

- 状态：进行中，按设计文档第 20 章的里程碑 M1→M9 推进
- 已交付（均有测试与真实库端到端证据，见 [changelog](changelog.md)）：
  - M1 身份体系：Owner JWT 与 Agent API Key 双轨、scope 判定、请求上下文与审计骨架
  - M2 画像 / 简历版本 / 项目：可见性三档阶梯、“每用户仅一份当前简历”
  - M3 工作记录：录入幂等、标签与全文检索、可见性调整与归档
  - M4 记忆引擎：异步提取闭环、去重与冲突检测、风险分级与审核、版本替代
  - M5 Context Builder：布尔检索与 LIKE 兜底、加权评分、去相似、Token 预算、访问审计
- 下一里程碑：M6 MCP Server（Streamable HTTP + Resources + 核心 Tools）。
  SDK 已选定并验证可从镜像拉取，见 [ADR-026](decisions.md)
- 明确未做：MCP Server（M6）、Skill 与工作风格（M7）、报告能力（M8）、生产化与数据生命周期（M9）；
  `GET /api/v1/audit-logs` 只有写入侧，调用返回 404
- 已知约束（会直接改变设计选择，细节见 [conventions](conventions.md)）：
  - MySQL 无行级安全，多用户隔离只能由应用层补偿（ADR-020）
  - MySQL 不支持部分索引，条件唯一约束用 STORED 生成列表达（ADR-021）
  - `ngram_token_size = 2` 是服务器只读参数，单字查询必须由 LIKE 兜底
  - 目标实例 MySQL 9.3.0 高于 Flyway 9.22.3 官方支持上限，迁移可用但有告警

## 代码位置

- `src/main/java/com/example/pwt/app` - 启动类、装配、多租户插件配置、Web 拦截器与 Owner 引导
- `src/main/java/com/example/pwt/common` - 统一响应、错误码、异常处理、UUIDv7、脱敏与 Token 估算
- `src/main/java/com/example/pwt/identity` - 用户、Agent Client、Token、scope、`AccessPolicy` 可见性判定
- `src/main/java/com/example/pwt/profile|work|memory|context` - 四个业务领域，各自分
  application / controller / domain / infrastructure 四层
- `src/main/java/com/example/pwt/task` - 异步任务队列：领取事务、Worker、租约回收（跨领域基础设施）
- `src/main/java/com/example/pwt/llm` - LLM Provider 抽象与 OpenAI 兼容实现
- `src/main/java/com/example/pwt/audit` - 审计写入（独立事务，失败不拖垮主业务）
- `src/main/java/com/example/pwt/publicweb` - 匿名公开入口
- `src/main/resources/db/migration` - Flyway V1~V10
- `src/test/java/com/example/pwt/migration` - 在真实 MySQL 上校验 DDL 行为的测试
- `README.md` - 面向人的构建 / 启动 / 接口速览与逐项验证记录

## 开发库现状

开发库 `personal_test` 已由 Flyway 建到 V10，且含 M2~M5 各轮端到端验证留下的业务数据，**不是空库**。
DDL 测试使用独立的临时库 `pwt_ddl_test`，用完即删，不触碰 `personal_test`。
