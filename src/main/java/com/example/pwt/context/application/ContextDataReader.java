package com.example.pwt.context.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.common.api.Scope;
import com.example.pwt.context.domain.ContextQuery;
import com.example.pwt.context.infrastructure.ConstraintEntity;
import com.example.pwt.context.infrastructure.StyleRuleEntity;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.application.MemoryRecall;
import com.example.pwt.memory.application.MemoryRecallService;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceMapper;
import com.example.pwt.profile.infrastructure.UserProfileEntity;
import com.example.pwt.profile.infrastructure.UserProfileMapper;
import com.example.pwt.work.domain.ProjectStatus;
import com.example.pwt.work.infrastructure.ProjectEntity;
import com.example.pwt.work.infrastructure.ProjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上下文数据装载：在同一个只读事务内取齐一次构建需要的数据，并映射为可排序的候选
 * （设计文档 11.3 第 2～4 步）。
 *
 * <p>集中成一个只读事务有两个作用：候选记忆与它们引用的工作记录、画像之间不会读到跨提交的
 * 中间态；同时整次构建只占用一次连接，便于把主事务保持短（11.4）。
 *
 * <p>本类只做“取数 + 搬运字段”，不排序、不截预算：那部分是纯函数，见 {@link ContextComposer}。
 *
 * @author pwt
 */
@Service
public class ContextDataReader {

    /**
     * 单条记忆注入文本的长度上限。
     *
     * <p>记忆正文允许 10000 字（9.4），一条长记忆就能占满 4000 token 的预算。
     * 优先用摘要，没有摘要时截正文：截断而不是丢弃，是因为摘要缺失的记忆往往是
     * Owner 手写的重要条目，丢掉比只看到前半句更糟。
     */
    private static final int MAX_INJECT_CHARS = 600;

    private final UserProfileMapper userProfileMapper;

    private final ProjectMapper projectMapper;

    private final GovernanceQueryService governanceQueryService;

    private final MemoryRecallService memoryRecallService;

    private final MemoryEvidenceMapper evidenceMapper;

    /**
     * 构造装载器。
     *
     * @param userProfileMapper   画像 Mapper
     * @param projectMapper       项目 Mapper
     * @param governanceQueryService 约束与风格规则查询（与 MCP Resources 共用）
     * @param memoryRecallService 记忆召回服务
     * @param evidenceMapper      记忆证据 Mapper，用于补出来源引用
     */
    public ContextDataReader(UserProfileMapper userProfileMapper, ProjectMapper projectMapper,
                             GovernanceQueryService governanceQueryService,
                             MemoryRecallService memoryRecallService, MemoryEvidenceMapper evidenceMapper) {
        this.userProfileMapper = userProfileMapper;
        this.projectMapper = projectMapper;
        this.governanceQueryService = governanceQueryService;
        this.memoryRecallService = memoryRecallService;
        this.evidenceMapper = evidenceMapper;
    }

    /**
     * 装载一次构建所需的数据。
     *
     * @param actor      当前主体
     * @param query      已组装的检索串
     * @param projectId  项目，可为空
     * @param types      记忆类型白名单，为空表示不限
     * @param candidates 候选上限
     * @return 装载结果
     */
    @Transactional(readOnly = true)
    public Loaded load(ActorContext actor, ContextQuery query, UUID projectId, List<MemoryType> types,
                       int candidates) {
        Instant now = Instant.now();
        MemoryRecall recall = memoryRecallService.recall(actor, query, types,
            projectId == null ? null : projectId.toString(), candidates);
        List<MemoryEntity> memories = recall.candidates();
        Map<UUID, List<UUID>> refs = sourceRefs(memories);
        List<ContextCandidate> items = memories.stream()
            .map(memory -> toCandidate(memory, refs.get(UUID.fromString(memory.getId()))))
            .toList();
        return new Loaded(readProfile(actor), readProject(actor, projectId), readConstraints(actor),
            readStyleRules(actor), items, recall.fullText(), now);
    }

    /**
     * 一次装载的结果。
     *
     * @param profile     用户画像，未初始化或无权读取时为空
     * @param project     项目背景，未指定项目时为空
     * @param constraints 生效约束，已按严重程度排好序
     * @param styleRules  已批准的风格规则
     * @param candidates  记忆候选，保持召回顺序
     * @param fullText    是否由全文检索命中；false 表示走了 {@code LIKE} 兜底
     * @param now         基准时刻：新近度与有效期必须同源，两次取时跨分钟会得出互相矛盾的结果
     */
    public record Loaded(UserProfileEntity profile, ProjectEntity project, List<ConstraintEntity> constraints,
                         List<StyleRuleEntity> styleRules, List<ContextCandidate> candidates, boolean fullText,
                         Instant now) {
    }

    private ContextCandidate toCandidate(MemoryEntity memory, List<UUID> refs) {
        return new ContextCandidate(UUID.fromString(memory.getId()), memory.getMemoryType(),
            injectText(memory), memory.getImportance(), memory.getConfidence(), memory.getCreatedAt(),
            memory.getLastUsedAt(), memory.getProjectId() == null ? null : UUID.fromString(memory.getProjectId()),
            memory.getRelevance(), memory.getRiskLevel(), refs);
    }

    private String injectText(MemoryEntity memory) {
        String summary = memory.getSummary();
        String text = summary == null || summary.isBlank() ? memory.getContent() : summary;
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= MAX_INJECT_CHARS
            ? stripped
            : stripped.substring(0, MAX_INJECT_CHARS) + "…";
    }

    private UserProfileEntity readProfile(ActorContext actor) {
        // 画像按 profile:read 判定，而不是按 context:build 一并放行：
        // 只授予 context:build 的 Agent 不应通过上下文包旁路读到 private 画像（14.2）
        if (!actor.hasScope(Scope.PROFILE_READ)) {
            return null;
        }
        UserProfileEntity profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
            .eq(UserProfileEntity::getUserId, actor.userId().toString()));
        return profile != null && AccessPolicy.readableBy(actor, profile.getVisibility()) ? profile : null;
    }

    private ProjectEntity readProject(ActorContext actor, UUID projectId) {
        if (projectId == null) {
            return null;
        }
        ProjectEntity project = projectMapper.selectById(projectId.toString());
        if (project == null || !actor.userId().toString().equals(project.getUserId())) {
            return null;
        }
        boolean usable = !ProjectStatus.ARCHIVED.getValue().equals(project.getStatus())
            && AccessPolicy.readableBy(actor, project.getVisibility());
        return usable ? project : null;
    }

    private List<ConstraintEntity> readConstraints(ActorContext actor) {
        return governanceQueryService.activeConstraints(actor);
    }

    private List<StyleRuleEntity> readStyleRules(ActorContext actor) {
        return governanceQueryService.approvedStyleRules(actor);
    }

    private Map<UUID, List<UUID>> sourceRefs(List<MemoryEntity> memories) {
        if (memories.isEmpty()) {
            return Map.of();
        }
        List<String> ids = memories.stream().map(MemoryEntity::getId).toList();
        Map<UUID, List<UUID>> refs = new LinkedHashMap<>();
        for (var evidence : evidenceMapper.selectWorkLogRefs(ids)) {
            if (evidence.getMemoryId() == null || evidence.getWorkLogId() == null) {
                continue;
            }
            refs.computeIfAbsent(UUID.fromString(evidence.getMemoryId()), key -> new ArrayList<>())
                .add(UUID.fromString(evidence.getWorkLogId()));
        }
        return refs;
    }
}
