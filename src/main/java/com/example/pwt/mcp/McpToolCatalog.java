package com.example.pwt.mcp;

import com.example.pwt.context.application.ContextBuilderService;
import com.example.pwt.context.application.ContextPacket;
import com.example.pwt.context.application.ContextSearchService;
import com.example.pwt.context.controller.dto.ContextPacketResponse;
import com.example.pwt.context.controller.dto.MemorySearchResponse;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.application.MemoryProposeCommand;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.memory.controller.dto.MemoryResponse;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.profile.controller.dto.ProfileResponse;
import com.example.pwt.profile.controller.dto.ResumeResponse;
import com.example.pwt.skill.application.SkillRecallService;
import com.example.pwt.skill.controller.dto.SkillResponse;
import com.example.pwt.work.application.WorkLogCommand;
import com.example.pwt.work.application.WorkLogService;
import com.example.pwt.work.controller.dto.WorkLogResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具清单（设计文档 9.3、9.4）。
 *
 * <p>每个处理函数只做三件事：按 9.4 的 Schema 取参、调用领域服务、包装结果。
 * 权限、幂等、长度与取值校验全部留在服务层，MCP 侧不重复实现——这是 9.1
 * 「MCP 是 Agent 访问层，不是业务逻辑层」的落实，也让同一个动作经 REST 与 MCP
 * 两条入口得到完全一致的判定结果。
 *
 * <p>输出复用 REST 的响应 DTO：同一份数据在两个入口有两种字段名，
 * 会让 Agent 与人都要记两套形状，也会让两侧独立漂移。
 *
 * <p>M6 注册六个工具，M7 补上 {@code get_skills}；报告类工具属 M8，
 * 与其注册一个恒返回空的占位工具（客户端会误读成“没有技能”），
 * 不如让它们在 {@code tools/list} 里干脆不出现。
 *
 * @author pwt
 */
public class McpToolCatalog {

    private final WorkLogService workLogService;

    private final MemoryService memoryService;

    private final ContextSearchService contextSearchService;

    private final ContextBuilderService contextBuilderService;

    private final ProfileService profileService;

    private final ResumeService resumeService;

    private final SkillRecallService skillRecallService;

    private final McpProperties properties;

    /**
     * 构造清单。
     *
     * @param workLogService        工作记录服务
     * @param memoryService         记忆服务
     * @param contextSearchService  记忆检索服务
     * @param contextBuilderService 上下文构建服务
     * @param profileService        画像服务
     * @param resumeService         简历服务
     * @param skillRecallService    Skill 召回服务
     * @param properties            MCP 配置
     */
    public McpToolCatalog(WorkLogService workLogService, MemoryService memoryService,
                          ContextSearchService contextSearchService,
                          ContextBuilderService contextBuilderService, ProfileService profileService,
                          ResumeService resumeService, SkillRecallService skillRecallService,
                          McpProperties properties) {
        this.workLogService = workLogService;
        this.memoryService = memoryService;
        this.contextSearchService = contextSearchService;
        this.contextBuilderService = contextBuilderService;
        this.profileService = profileService;
        this.resumeService = resumeService;
        this.skillRecallService = skillRecallService;
        this.properties = properties;
    }

    /**
     * 要注册的全部工具。
     *
     * @return 工具规格列表
     */
    public List<SyncToolSpecification> tools() {
        return List.of(getProfile(), getResumeUrl(), captureWorkLog(), proposeMemory(),
            searchMemories(), buildContext(), getSkills());
    }

    private SyncToolSpecification getProfile() {
        Tool tool = tool("get_profile",
            "获取用户的个人画像：一句话职业定位与自述摘要。用于了解你在为谁工作。",
            McpSchemas.objectSchema(Map.of(), List.of()));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "get_profile",
                () -> ProfileResponse.from(profileService.read(McpContext.actor(exchange)))));
    }

    private SyncToolSpecification getResumeUrl() {
        Tool tool = tool("get_resume_url",
            "获取当前简历的访问地址。只有简历可见性为 public 时地址才对外可访；"
                + "没有简历或无权读取时报错。",
            McpSchemas.objectSchema(Map.of(), List.of()));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "get_resume_url", () -> {
                ActorContext actor = McpContext.actor(exchange);
                ResumeResponse resume = ResumeResponse.from(resumeService.readCurrent(actor));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("resumeId", resume.id());
                data.put("version", resume.version());
                data.put("title", resume.title());
                data.put("visibility", resume.visibility());
                // 是否真的能被外部匿名访问，取决于可见性而不是有没有链接文本
                data.put("publicVisible", resume.publicUrl() != null);
                data.put("url", resume.publicUrl() == null ? null : absolute(resume.publicUrl()));
                return data;
            }));
    }

    private SyncToolSpecification captureWorkLog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_id", McpSchemas.string("关联项目 ID，需属于当前用户", "uuid"));
        properties.put("title", McpSchemas.string("标题", null));
        properties.put("summary", McpSchemas.string("一句话摘要", null));
        properties.put("content", McpSchemas.string("详细过程与产出", null));
        properties.put("work_type", McpSchemas.stringEnum(McpSchemas.WORK_TYPES,
            "工作类型，缺省 general"));
        properties.put("occurred_at", McpSchemas.string("发生时间，ISO-8601，如 2026-11-18T09:30:00Z",
            "date-time"));
        properties.put("tags", McpSchemas.stringArray("标签，入库前统一转小写", null));
        properties.put("visibility", McpSchemas.stringEnum(McpSchemas.AGENT_VISIBILITIES,
            "可见性，缺省 private；工具不能把内容直接发布为 public"));
        properties.put("idempotency_key", McpSchemas.string(
            "幂等键：同一次写入的网络重试带上同一个键，不会重复入库", null));
        Tool tool = tool("capture_work_log",
            "记录一次工作。建议带上 idempotency_key；写入后服务端会自动排队记忆提取，"
                + "返回的 memoryExtraction 是提取进度。",
            McpSchemas.objectSchema(properties, List.of("title", "occurred_at")));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "capture_work_log", () -> {
                ActorContext actor = McpContext.actor(exchange);
                McpArgs args = new McpArgs(arguments);
                WorkLogCommand command = new WorkLogCommand(
                    args.optionalUuid("project_id"),
                    args.requiredString("title"),
                    args.optionalString("summary"),
                    args.optionalString("content"),
                    args.optionalString("work_type"),
                    args.requiredInstant("occurred_at"),
                    args.stringList("tags"),
                    args.optionalString("visibility"),
                    args.optionalString("idempotency_key"));
                var created = workLogService.create(actor, command);
                // 取响应对象里的真实进度：幂等命中时它回读的是既有任务状态，而不是照报未排队
                return WorkLogResponse.from(created.entity(), created.extraction());
            }));
    }

    private SyncToolSpecification proposeMemory() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memory_type", McpSchemas.stringEnum(McpSchemas.MEMORY_TYPES, "记忆类型"));
        properties.put("content", McpSchemas.string("记忆正文", null));
        properties.put("summary", McpSchemas.string("一句话摘要，用于审核界面与上下文注入", null));
        properties.put("evidence", McpSchemas.string("支撑该结论的原文摘录", null));
        properties.put("source_work_log_id", McpSchemas.string("来源工作记录 ID", "uuid"));
        properties.put("visibility", McpSchemas.stringEnum(McpSchemas.AGENT_VISIBILITIES,
            "可见性，缺省 private"));
        properties.put("confidence", McpSchemas.number("你的置信度 0~1；服务端仍会重新判定处置方式", 0.0D, 1.0D));
        properties.put("expires_at", McpSchemas.string("过期时间，ISO-8601", "date-time"));
        Tool tool = tool("propose_memory",
            "提交一条长期记忆候选。服务端会重新判定风险与处置，不能指定状态或风险等级；"
                + "只有 Owner 审核通过后才会进入上下文。返回的 status 才是最终处置结果。",
            McpSchemas.objectSchema(properties, List.of("memory_type", "content")));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "propose_memory", () -> {
                ActorContext actor = McpContext.actor(exchange);
                McpArgs args = new McpArgs(arguments);
                MemoryProposeCommand command = new MemoryProposeCommand(
                    args.requiredString("memory_type"),
                    args.requiredString("content"),
                    args.optionalString("summary"),
                    args.optionalString("evidence"),
                    args.optionalUuid("source_work_log_id"),
                    args.optionalString("visibility"),
                    args.optionalDouble("confidence"),
                    args.optionalInstant("expires_at"),
                    Map.of());
                return MemoryResponse.from(memoryService.propose(actor, command));
            }));
    }

    private SyncToolSpecification searchMemories() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", McpSchemas.string("关键词或自然语言短语", null));
        properties.put("memory_types", McpSchemas.stringArray("类型过滤，缺省不限",
            McpSchemas.MEMORY_TYPES));
        properties.put("project_id", McpSchemas.string("项目过滤", "uuid"));
        properties.put("limit", McpSchemas.integer("返回条数 1~50，缺省 10"));
        Tool tool = tool("search_memories",
            "按关键词检索记忆。只返回已批准且未过期的记忆。"
                + "recall_path 为 like 表示走了子串兜底（例如单字查询），命中质量低于全文检索。",
            McpSchemas.objectSchema(properties, List.of("query")));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "search_memories", () -> {
                ActorContext actor = McpContext.actor(exchange);
                McpArgs args = new McpArgs(arguments);
                return MemorySearchResponse.from(contextSearchService.search(actor,
                    args.requiredString("query"), args.stringList("memory_types"),
                    args.optionalUuid("project_id"), args.optionalInt("limit")));
            }));
    }

    private SyncToolSpecification buildContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", McpSchemas.string("当前要做的任务描述", null));
        properties.put("project_id", McpSchemas.string("项目上下文；指定后只取该项目与项目未知的记忆", "uuid"));
        properties.put("token_budget", McpSchemas.integer("Token 预算 500~20000，缺省 4000"));
        properties.put("include_types", McpSchemas.stringArray("记忆类型过滤，缺省不限",
            McpSchemas.MEMORY_TYPES));
        properties.put("include_skills", McpSchemas.bool("是否包含 Skill，缺省 true"));
        Tool tool = tool("build_context",
            "为当前任务构建上下文包：少量相关、可信、有效且你有权读取的记忆、约束与风格规则，"
                + "受 token_budget 限制。被截断的部分在 context_meta.truncated_sections 里标明，"
                + "因此结果为空不代表用户没有相关记忆。",
            McpSchemas.objectSchema(properties, List.of("task")));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "build_context", () -> {
                ActorContext actor = McpContext.actor(exchange);
                McpArgs args = new McpArgs(arguments);
                ContextPacket packet = contextBuilderService.build(actor,
                    args.requiredString("task"), args.optionalUuid("project_id"),
                    args.optionalInt("token_budget"), args.stringList("include_types"),
                    args.optionalBoolean("include_skills"));
                return ContextPacketResponse.from(packet);
            }));
    }

    private SyncToolSpecification getSkills() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", McpSchemas.string("按内容找 Skill 的关键词，不填则按 skill_ids 或最新已发布取用", null));
        properties.put("skill_ids", McpSchemas.uuidArray("指定要取的 Skill ID，与 query 同时给出时取交集", 20));
        properties.put("limit", McpSchemas.integer("返回条数 1~50，缺省 10"));
        Tool tool = tool("get_skills",
            "获取已发布的 Skill（可执行的工作方法），返回完整 Markdown 正文，可直接按步骤执行。"
                + "只返回已发布且你有权读取的条目，草稿与已归档不在内；"
                + "skill_ids 里已失效或已归档的 ID 会静默跳过，不会报错。",
            McpSchemas.objectSchema(properties, List.of()));
        return new SyncToolSpecification(tool, (exchange, arguments) ->
            McpInvocation.tool(exchange, "get_skills", () -> {
                ActorContext actor = McpContext.actor(exchange);
                McpArgs args = new McpArgs(arguments);
                return skillRecallService.getSkills(actor, args.optionalString("query"),
                        args.uuidList("skill_ids", 20), args.optionalInt("limit"))
                    .stream().map(SkillResponse::from).toList();
            }));
    }

    private static Tool tool(String name, String description, JsonSchema input) {
        return Tool.builder().name(name).description(description).inputSchema(input).build();
    }

    /**
     * 把站内相对路径补成绝对地址。
     *
     * <p>REST 的 {@code publicUrl} 一直是相对路径（给浏览器用，天然跟随部署域名）；
     * Agent 拿到相对路径无法直接使用，因此这里按配置补前缀。
     * 未配置 {@code pwt.mcp.public-base-url} 时保持相对路径而不伪造一个主机名。
     *
     * @param path 相对路径
     * @return 绝对地址或原始相对路径
     */
    private String absolute(String path) {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return path;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path;
    }
}
