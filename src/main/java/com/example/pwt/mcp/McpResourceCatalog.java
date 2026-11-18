package com.example.pwt.mcp;

import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.Scope;
import com.example.pwt.context.application.GovernanceQueryService;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.memory.domain.MemoryStatus;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.profile.controller.dto.ProfileResponse;
import com.example.pwt.profile.controller.dto.ResumeResponse;
import com.example.pwt.work.application.WorkLogService;
import com.example.pwt.work.controller.dto.WorkLogResponse;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP Resources（设计文档 9.2）。
 *
 * <p>Resources 只承载“相对稳定、可枚举”的读取，写入一律走 Tools。
 * 权限沿用各服务自己的判定（{@code profile:read} / {@code memory:read}），
 * MCP 侧不另开口子：缺 scope 时服务抛 {@code SCOPE_REQUIRED}，由执行外壳转成统一错误结构。
 *
 * <p>{@code pwt://memory/approved} 按 9.2 的要求不一次性返回无限量内容：
 * 固定资源只给第一页，另外注册一个按类型取用的模板，
 * 并在描述里引导客户端优先使用 {@code search_memories} 与 {@code build_context}。
 *
 * <p>模板资源要能被 {@code resources/read} 读到，必须把展开后的具体 URI 逐个注册成资源：
 * SDK 的读取分发是拿请求 URI 去匹配<b>已注册资源</b>的 URI 模板，
 * {@code resources/templates/list} 返回的模板只用于展示，不参与匹配。
 * 因此这里按六种记忆类型展开，非法类型自然落到“资源不存在”，
 * 而不是静默返回空列表。
 *
 * @author pwt
 */
public class McpResourceCatalog {

    /** 已批准记忆资源的默认返回条数。 */
    private static final int APPROVED_MEMORY_SIZE = 20;

    /** 按类型取用的记忆模板 URI。 */
    static final String MEMORY_BY_TYPE_TEMPLATE = "pwt://memory/approved/{memory_type}";

    /** 按类型取用的记忆资源 URI 前缀，模板按类型展开时拼在这里。 */
    private static final String MEMORY_BY_TYPE_PREFIX = "pwt://memory/approved/";

    /** 最近工作记录资源的返回条数。 */
    private static final int RECENT_WORK_LOG_SIZE = 20;

    private final ProfileService profileService;

    private final ResumeService resumeService;

    private final GovernanceQueryService governanceQueryService;

    private final MemoryService memoryService;

    private final WorkLogService workLogService;

    /**
     * 构造目录。
     *
     * @param profileService         画像服务
     * @param resumeService          简历服务
     * @param governanceQueryService 约束与风格规则查询
     * @param memoryService          记忆服务
     * @param workLogService         工作记录服务
     */
    public McpResourceCatalog(ProfileService profileService, ResumeService resumeService,
                              GovernanceQueryService governanceQueryService, MemoryService memoryService,
                              WorkLogService workLogService) {
        this.profileService = profileService;
        this.resumeService = resumeService;
        this.governanceQueryService = governanceQueryService;
        this.memoryService = memoryService;
        this.workLogService = workLogService;
    }

    /**
     * 可直接读取的资源，按 URI 索引。
     *
     * @return URI → 读取规格
     */
    public Map<String, SyncResourceSpecification> resources() {
        Map<String, SyncResourceSpecification> map = new LinkedHashMap<>();
        put(map, "pwt://profile/identity", "个人身份摘要",
            "Owner 的一句话职业定位与自述摘要。需要 profile:read。",
            () -> ProfileResponse.from(profileService.read(scope(Scope.PROFILE_READ))));
        put(map, "pwt://profile/resume", "当前简历",
            "当前生效的简历版本，含正文与结构化字段。需要 profile:read。",
            () -> ResumeResponse.from(resumeService.readCurrent(scope(Scope.PROFILE_READ))));
        put(map, "pwt://profile/style", "已批准工作风格",
            "已批准的工作风格规则，用于控制回答方式与交付格式。需要 profile:read。",
            () -> governanceQueryService.approvedStyleRules(scope(Scope.PROFILE_READ)));
        put(map, "pwt://profile/constraints", "当前有效约束",
            "必须遵守或禁止的事项，按严重程度倒序。需要 profile:read。",
            () -> governanceQueryService.activeConstraints(scope(Scope.PROFILE_READ)));
        put(map, "pwt://memory/approved", "已批准记忆摘要",
            "已批准记忆的第一页（" + APPROVED_MEMORY_SIZE + " 条）。"
                + "按条件取用请改用 search_memories 或 build_context。需要 memory:read。",
            () -> memoryService.list(scope(Scope.MEMORY_READ), null,
                MemoryStatus.APPROVED.getValue(), PageQuery.of(1, APPROVED_MEMORY_SIZE)));
        put(map, "pwt://work/recent", "最近工作记录",
            "最近 " + RECENT_WORK_LOG_SIZE + " 条工作记录，按发生时间倒序，含正文与标签。"
                + "可见性由服务层判定，Agent 读不到 private 记录。需要 work:read。",
            () -> {
                PageResult<WorkLogEntity> page = workLogService.list(scope(Scope.WORK_READ),
                    null, null, null, PageQuery.of(1, RECENT_WORK_LOG_SIZE));
                return PageResult.of(page.items().stream().map(WorkLogResponse::from).toList(),
                    page.page(), page.size(), page.total());
            });
        // 模板展开成具体资源：客户端列一次就能拿到全部可读 URI，不必猜类型段
        for (MemoryType type : MemoryType.values()) {
            String memoryType = type.getValue();
            put(map, MEMORY_BY_TYPE_PREFIX + memoryType, "已批准记忆（" + memoryType + "）",
                "类型为 " + memoryType + " 的已批准记忆第一页（" + APPROVED_MEMORY_SIZE
                    + " 条）。需要 memory:read。",
                () -> memoryService.list(scope(Scope.MEMORY_READ), memoryType,
                    MemoryStatus.APPROVED.getValue(), PageQuery.of(1, APPROVED_MEMORY_SIZE)));
        }
        return map;
    }

    /**
     * 资源模板：按记忆类型取用已批准记忆。
     *
     * @return 模板列表
     */
    public List<ResourceTemplate> resourceTemplates() {
        // 用 6 参显式构造：5 参重载的第 4 位是 description、第 5 位才是 annotations，
        // 少写一个参数虽然能编译通过，但会把 MIME 塞进 description、把 null 当成 MIME，
        // 客户端拿到的说明与内容类型都是错的
        return List.of(new ResourceTemplate(MEMORY_BY_TYPE_TEMPLATE, "按类型取用已批准记忆",
            "approved-memories-by-type", "指定类型的已批准记忆第一页。需要 memory:read。",
            McpInvocation.MIME_TYPE, null));
    }

    private static void put(Map<String, SyncResourceSpecification> map, String uri, String name,
                            String description, Supplier<Object> action) {
        map.put(uri, fixed(uri, name, description, action));
    }

    /**
     * 组装一个 URI 固定的资源。
     *
     * @param uri         资源 URI
     * @param name        名称
     * @param description 说明
     * @param action      取数动作
     * @return 读取规格
     */
    static SyncResourceSpecification fixed(String uri, String name, String description,
                                          Supplier<Object> action) {
        return new SyncResourceSpecification(resource(uri, name, description),
            (exchange, request) -> McpInvocation.resource(exchange, uri, uri, action));
    }

    /**
     * 组装资源描述。
     *
     * @param uri         URI
     * @param name        名称
     * @param description 说明，客户端唯一能读到的提示
     * @return 资源
     */
    static Resource resource(String uri, String name, String description) {
        return Resource.builder().uri(uri).name(name).title(name).description(description)
            .mimeType(McpInvocation.MIME_TYPE).build();
    }

    /**
     * 取当前主体并要求指定 scope。
     *
     * <p>主体由 {@link McpInvocation} 在调用开始时绑定到线程上，此处直接取用；require() 取不到时抛未认证，不会带着空主体去查询。
     * scope 在这里显式要求一次：服务内部同样会校验，但资源读取的入口在 MCP 侧，
     * 早一步失败能让日志与错误信息指向具体资源。
     *
     * @param requiredScope 需要的 scope
     * @return 主体
     */
    private static ActorContext scope(String requiredScope) {
        ActorContext actor = ActorContextHolder.require();
        actor.requireScope(requiredScope);
        return actor;
    }
}
