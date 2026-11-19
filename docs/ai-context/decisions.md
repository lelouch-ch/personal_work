# 决策

ADR 风格的关键决策与理由，最新在前。已被取代的决策标注而不删除。

**ADR-001 ~ ADR-025 的正文在设计文档
`Personal_Work_Twin_完整技术设计文档_v1.1_mysql.md` 第 18 章的决策表里，不在本文件复制。**
本文件只保留索引、后续新增的决策，以及“只有看过代码才看得出来”的补充后果。

## ADR-029 - 风格规则不设自动批准阈值

- Date: 2026-11-19
- Status: accepted
- Context: 记忆有 6.3 的证据条数与置信度阈值决定能否自动批准。风格规则表
  （`pt_style_rule`）同样带 `source_count` 与 `confidence`，实现时很容易被顺手套用同一套阈值。
- Decision: 只保留 `candidate → approved | archived` 三态，批准与否完全由 Owner 判定，
  服务端不强设阈值；两个数值仅作为 Owner 决策时的展示依据。
- Alternatives: 套用记忆的阈值——但 M4 的 LLM 提取只产六类记忆，风格规则至今没有任何自动产出方，
  行只能由 Owner 手写，`source_count` 恒为 1，用阈值卡自己刚写下的规则只会制造无人处理的待审队列。
- Consequences: 若将来接入“从工作记录归纳风格”的自动产出，阈值判定要作为新增能力重新设计，
  而不是在现有 `StyleRuleService` 里加一个 if。关联：设计文档 7 章 skill 模块的工作风格部分。

## ADR-028 - Skill 用「发布时归档同 slug 旧版本」表达版本切换

- Date: 2026-11-19
- Status: accepted
- Context: M7 要让 Skill 支持版本与发布。设计文档 8.3 的唯一键是
  `uq_skill_user_slug_version`（同 slug 多版本天然可共存），但没规定“哪一条是生效版本”，
  15.2 又要求「Skill 发布前由 Owner 审核」。若允许就地改写已发布内容，
  Agent 正在照做的流程会在两次调用之间被换掉，那次改写实际绕过了发布这道审核。
- Decision: 草稿原地改；对已发布行修改一律新建 `version + 1` 的草稿并原样返回它；
  `publish` 在同一事务内把同 slug 的其它已发布版本转 `archived`，从而保证同一 slug
  最多一条 `published`。归档不允许直接发布，必须重新走新建版本。版本号取该 slug
  历史最大值 + 1，不过滤状态。
- Alternatives:
  - 加 `is_current` 生成列 + 唯一索引表达“当前版本”（与 M2 的“每用户仅一份当前简历”同构）：
    多一个列与一条切换事务，且历史列表要额外过滤，收益不抵成本。
  - 允许就地改写已发布内容：实现最省，但审核语义失效。
  - 版本号按状态过滤后取最大值：会与归档版本撞唯一键。
- Consequences: 一次“改版”在库里是两行，列表页需要按 slug 聚合才好看（当前列表按更新时间
  平铺，尚未聚合）；`pt_skill.status` 的 `archived` 因此同时承载“主动废弃”和“被新版本取代”
  两种含义，回放时要靠 version 与时间区分。关联：设计文档 5.2、10.2、15.2。

## ADR-027 - ai-context 不复述设计文档的 ADR 正文

- Date: 2026-11-18
- Status: accepted
- Context: 初始化本知识库时，项目已有 25 条 ADR 与 1900 余行规格写在设计文档里，
  而 ai-context 技能要求 `decisions.md` 承载决策正文。照抄会产生两份真相。
- Decision: 设计文档仍是规格与 ADR 正文的唯一权威；`decisions.md` 只放索引、
  新增决策，以及对既有 ADR 的补充后果。要求与约定同样以“链接 + 一句话”指向设计文档章节，
  不整段搬运。
- Alternatives: 把 25 条 ADR 全量复制进来（会双向漂移，改一处忘一处）；
  把设计文档拆进 docs/（等于重写规格文档，风险和篇幅都不划算，且未获用户授权移动既有文档）。
- Consequences: 读决策需要跳转两个文件；好处是不会出现“设计文档改了、知识库没改”的过期正文。
  新增决策一律从这里开始写，不再往设计文档追加新编号。

## ADR-026 - MCP 接入采用官方 Java SDK 0.11.3

- Date: 2026-11-18
- Status: accepted
- Context: M6 要提供 MCP Streamable HTTP（设计文档 9.1、ADR-006）。仓库此前没有任何 MCP 依赖，
  本地 Maven 仓库也没有缓存；协议握手、会话、SSE 分帧与 schema 校验自己实现容易出错。
  用户已明确同意为此新增生产依赖。
- Decision: 引入 `io.modelcontextprotocol.sdk:mcp:0.11.3`，用其
  `HttpServletStreamableServerTransportProvider` 挂到现有 Servlet 栈上。
  服务端只做协议与传输：Tools 的处理函数直接调用各领域已有的 application 服务，
  鉴权、scope、幂等与审计全部留在服务层，MCP 侧不重复实现。
- Alternatives:
  - 手写 JSON-RPC 端点：零新依赖，但 `initialize` 握手、协议版本协商、SSE 分帧与
    schema 校验都要自维护，出错面大于收益。
  - 0.10.0：只有 SSE 传输，没有 Streamable HTTP，不符合 ADR-006。
  - 0.13.1 / 0.14.1 及以上：拆成 `mcp-core` + `mcp-json-jackson2`，且 1.x/2.x 已是
    另一套 API；0.11.3 是“含 Streamable HTTP 且仍是单 jar”的最低复杂度假选点。
  - `mcp-spring-webmvc`：额外的 Spring 集成层，本项目自己注册 Servlet 即可，不需要多一层依赖。
- Consequences: 依赖树多出 `reactor-core` 与 `json-schema-validator`；SDK 声明的
  jackson 2.17 / reactor 3.7 被 Boot 3.2.2 的 BOM 降为 2.15.3 / 3.6.2，
  这组降级已在 2026-11-18 的真实协议往返中验证通过（握手、会话、SSE 分帧、六个工具、
  十二个资源，见 `README.md` 的 M6 端到端记录）；但只有单连接功能证据，没有并发与负载证据。
  升级 Boot 或 SDK 时要重跑全量测试确认降级仍兼容；
  若 SDK 后续版本改了 API，需要评估迁移成本而不是随手升级。
  关联：ADR-005（MCP + REST 双入口）、ADR-006（Streamable HTTP 为远程基线）。

## 对既有 ADR 的补充（实施期才发现的后果）

- ADR-020（以业务层隔离替代 RLS）：多租户插件不是“不加条件就没事”的旁观者——它会重建
  SQL 并改变锁子句位置，导致 `FOR UPDATE SKIP LOCKED` 非法。凡带锁或带子句顺序要求的语句
  都要显式 `@InterceptorIgnore`。详见设计文档 8.4 第 17 条。
- ADR-021（生成列替代部分索引）：生成列的字符集与排序规则要显式指定，
  否则依赖列的 collation 推导在部分写法下静默改变判重语义。任务去重键的做法见 V9 迁移注释。
- ADR-022（审计与上下文访问日志不设 user_id 外键）：这两张表同时在免拦截白名单内，
  意味着写入时 `user_id` 必须由业务层显式给出。`build_context` 的主体是 Agent 而数据所有者
  是 Owner，若照抄主体的 id 会把访问日志记到错误用户名下。
- ADR-013（结构化过滤 + 全文检索优先）：`ngram_token_size = 2` 让整句直送布尔模式必然召回为 0
  （等价于要求原文出现连续同一段文字），因此“组装检索串”是这一决策的必要组成部分，
  不是可选优化。见设计文档 8.4 第 20 条。
- ADR-009（Context Builder）：预算分配必须是“固定段先扣、可变段拿剩余”。反过来给记忆段
  预留固定比例，会在约束多时把约束挤出上下文，而约束被丢弃的代价远高于少一条补充经验。
  M7 补一条实现期后果：Skill 段排在相关记忆之后，而 Markdown 流程截半截会误导 Agent
  以为那就是完整步骤，因此 Skill 段只能“按条给预算、装不下整条就不装”，
  不能像长文本那样截断填充。副作用是默认预算（4000）下 Skill 很可能一条都进不去。
- ADR-026（MCP SDK）：SDK 的 `resources/read` 只按**已注册资源**的 URI 做模板匹配，
  `resources/templates/list` 返回的清单纯展示、不参与匹配。因此模板必须展开成具体 URI
  逐个注册（本项目按六种记忆类型展开），否则客户端能列出却读不到，报“资源不存在”。
  另两处实测行为需知悉：裸 `ObjectMapper` 缺 JavaTime 模块会让所有含 `Instant` 的 DTO
  序列化失败（MCP 侧自建 mapper 时必须显式注册）；handler 抛 `McpError` 时 servlet 传输
  只记日志并返回空响应体，不是 JSON-RPC 错误帧。升级 SDK 后需复测这三点。
