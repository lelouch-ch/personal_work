# 需求

系统必须有的行为与可判定的验收标准，按能力分节。完整规格见设计文档，
这里只记录“已实现并验证”的契约与尚未闭合的缺口。

## 身份与授权

- Status: implemented
- Requirement: 数据所有者只由凭证决定；Owner 用 JWT 会话，Agent 用随机 API Key 加 scope。
- Acceptance criteria:
  - 缺凭证或凭证不可解析返回 401 `UNAUTHENTICATED`，响应体仍是统一结构并带 `requestId`
  - Agent 缺少所需 scope 时返回 403 `SCOPE_REQUIRED` 并说明缺哪个 scope
  - Agent 密钥明文只在创建响应里返回一次，库中只存 SHA-256 摘要；撤销后同一密钥立即 401
  - 客户端传入的 `user_id` 不参与判定，跨用户访问资源一律 404 而不是 403
- Constraints: Owner 不受 scope 限制；系统主体（Worker）读取按 Owner 同档，但不得写 `public`（ADR-024）
- Open questions: none

## 可见性

- Status: implemented
- Requirement: 三档可见性构成严格递增阶梯，判定集中在一个策略组件里。
- Acceptance criteria:
  - `private`：Owner 可读，Agent 与匿名均不可读
  - `agent`：Owner 可读；具备相应 scope 的 Agent 可读；匿名不可读
  - `public`：所有人可读
  - 非 Owner（含 Agent 与系统主体）提交 `visibility=public` 返回 400
- Constraints: 判定不得在查询里各写一套；匿名入口无主体上下文，查询必须自己限定 `user_id` 与可见性
- Open questions: none

## 工作记录

- Status: implemented
- Requirement: Agent 与 Owner 都能写入，重复提交不产生第二条记录，可按标签与关键词检索。
- Acceptance criteria:
  - 同一 `Idempotency-Key` 并发重复 POST 返回同一条记录，而不是报错也不是新建
  - 标签入库前统一小写归一；大写查询能命中小写存储的标签
  - 关键词检索对未闭合括号之类的输入仍能召回（自然语言模式），不静默返回 0 行
  - `source_type` 由凭证判定：Owner 会话写 `manual`，Agent 会话写 `agent` 并记录客户端 ID
  - 已归档记录不出现在默认列表
- Constraints: 尚无“修改正文”和“逻辑删除”接口；单字关键词需 LIKE 兜底（见下条）
- Open questions: none

## 记忆：提取与治理

- Status: implemented
- Requirement: “工作记录 → 候选记忆 → 审核 → 正式记忆”闭环成立，处置结论由服务端规则给出。
- Acceptance criteria:
  - 创建工作记录会在同一事务内排队提取任务；同一条记录不会排队两次
  - 提取任务的进度能通过工作记录详情读到真实状态，未排队时不谎称已排队
  - 高置信度、低风险、无冲突的客观事实可自动批准；偏好与经验需达到最低证据条数
  - 单条工作记录与其随附摘录只算一条证据
  - 重复内容不新建行，只累加证据；已批准的重复记忆只累加证据不改状态
  - 命中凭证类敏感信息：状态为拒绝、风险为 L3、正文替换为占位串，原文不出现在任何响应与留痕里
  - 审核动作与待审核队列仅 Owner 可调，Agent 一律 403
  - 修正已批准记忆产生新版本并指向旧版，旧版转为被替代；上下文只使用当前有效版本
- Constraints: 冲突检测是确定性规则，不处理跨句否定；向量相似度按 ADR-013 留待外挂
- Open questions: none

## 上下文构建

- Status: implemented
- Requirement: 针对具体任务返回少量、相关、可信、有效且授权可见的个人上下文，并受 Token 预算约束。
- Acceptance criteria:
  - 返回内容不含 candidate / rejected / archived / superseded 记忆，也不含过期或不在有效期内的记忆
  - 约束与风格规则在预算紧张时仍被注入；预算不足只削减相关记忆段，并在结果里标明被截断的段
  - 指定项目时，属于其它项目的记忆被排除，而项目未知的记忆仍保留
  - 中文短语走全文检索；单字查询降级为子串匹配，且结果里标明走了哪条召回路径
  - 同一件事的两种说法不会同时占预算；同一主题的两条不同事实不会被误合并
  - 每次构建写入一条访问记录（数据所有者、项目、注入的记忆 ID、预算与估算），并落审计
  - 被注入记忆的使用信号以一条批量更新回写，不逐行写，且失败不影响本次结果返回
  - `token_budget` 超出允许区间返回 400 且提示为中文
- Constraints: Token 估算是字符分档的保守上界而非模型 tokenizer；`skills` 段恒为空数组，待 M7
- Open questions: 权重与配额是否需要按用户/模型差异化；召回质量尚未用真实语料评测

## Agent 接入（MCP）

- Status: implemented
- Requirement: Agent 用同一张 API Key 通过 MCP 协议读写个人数据，权限判定与 REST 完全一致。
- Acceptance criteria:
  - 未认证请求返回 401 且响应体是统一错误结构；撤销后的 Key 立即失效
  - `initialize` 返回服务信息、capabilities 与中文 instructions，并下发会话标识；
    后续请求必须携带该会话标识
  - 六个工具可用：`get_profile`、`get_resume_url`、`capture_work_log`、`propose_memory`、
    `search_memories`、`build_context`；写工具真实落库并触发既有的提取任务与审计链路
  - 工具输出是统一包装（`ok` / `data` / `meta.request_id` / `error`）放在 text content 里，
    字段语义与 REST 一致，时间为 ISO-8601
  - 缺必填参数返回 `isError=true` + `VALIDATION_ERROR` 且点名具体字段；scope 不足返回
    `SCOPE_REQUIRED` 并说明缺哪个 scope；两者都不是协议层异常
  - 写工具的可见性入参不接受 `public`
  - 设计文档 9.2 表中的资源除 `pwt://skills/list`（属 M7）外全部提供：
    `profile/identity`、`profile/resume`、`profile/style`、`profile/constraints`、
    `memory/approved`、`work/recent`
  - `resources/list` 里的每个 URI 都必须能被 `resources/read` 读到；
    模板 `pwt://memory/approved/{memory_type}` 的六种类型逐个可读
  - 资源的可见性与 scope 判定与 REST 走同一套服务层规则，不因入口不同而放宽
  - 默认关闭时 `/mcp` 返回 404，REST 与其余测试不受影响
- Constraints: 主体必须由会话传到调用线程——多租户拦截器取不到主体时不追加 `user_id` 条件，
  带空主体查询会静默读到全部用户数据；MCP 侧不写业务查询，一律调用领域服务
- Open questions: 是否需要 MCP 专属的接入审计表（当前复用领域侧审计）；
  限流与 TLS 在 M9 落地后是否可默认开启

## 尚未成为需求的部分

- Skill 与工作风格（M7）、周报 / 学习建议 / 趋势分析（M8）、
  生产化与数据生命周期（M9）：规格在设计文档，实现未开始，
  此处不预先写成需求以免与实现脱节。
- 审计记录查询接口：只有写入侧，读取接口待 M9。
