package com.example.pwt.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.context.ContextProperties;
import com.example.pwt.context.domain.ContextQuery;
import com.example.pwt.context.infrastructure.ConstraintEntity;
import com.example.pwt.context.infrastructure.ContextAccessLogEntity;
import com.example.pwt.context.infrastructure.ContextAccessLogMapper;
import com.example.pwt.context.infrastructure.StyleRuleEntity;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.infrastructure.MemoryMapper;
import com.example.pwt.profile.infrastructure.UserProfileEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Context Builder 的预算分配与治理行为测试（设计文档 11.4、11.5、15.2、15.3）。
 *
 * <p>只 mock 数据装载，评分与预算走真实的 {@link ContextComposer}：
 * 这里要验证的正是“固定段先扣、可变段拿剩余”的分配顺序，把它 mock 掉就什么都没验证。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class ContextBuilderServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID AGENT_ID = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    @Mock
    private ContextDataReader dataReader;

    @Mock
    private ContextAccessLogMapper accessLogMapper;

    @Mock
    private MemoryMapper memoryMapper;

    @Mock
    private AuditRecorder auditRecorder;

    private ContextProperties properties;

    private ContextBuilderService service;

    private ActorContext owner;

    private ActorContext agent;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        service = new ContextBuilderService(dataReader, properties, accessLogMapper, memoryMapper,
            auditRecorder);
        owner = ActorContext.owner(USER_ID);
        agent = ActorContext.agent(USER_ID, AGENT_ID,
            Set.of(Scope.CONTEXT_BUILD, Scope.PROFILE_READ, Scope.MEMORY_READ));
    }

    @Test
    void agentWithoutScopeShouldBeRejected() {
        ActorContext noScope = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.MEMORY_READ));

        assertThatThrownBy(() -> service.build(noScope, "修复上传", null, null, null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining(Scope.CONTEXT_BUILD);
    }

    @Test
    void blankTaskShouldBeRejected() {
        assertThatThrownBy(() -> service.build(owner, "   ", null, null, null, null))
            .isInstanceOf(BizException.class);
    }

    @Test
    void shouldDefaultBudgetFromConfiguration() {
        given(emptyLoaded());

        ContextPacket packet = service.build(owner, "修复上传问题", null, null, null, null);

        assertThat(packet.meta().tokenBudget()).isEqualTo(properties.getDefaultTokenBudget());
    }

    @Test
    void shouldReportCandidatesAndSelectedCounts() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(),
            List.of(candidate("fact", "事实一"), candidate("fact", "事实二")), true, List.of(), NOW));

        ContextPacket packet = service.build(owner, "修复上传问题", null, 4000, List.of(), null);

        assertThat(packet.meta().totalCandidates()).isEqualTo(2);
        assertThat(packet.meta().selectedCount()).isEqualTo(2);
        assertThat(packet.meta().recallPath()).isEqualTo(ContextPacket.Meta.PATH_FULL_TEXT);
    }

    @Test
    void shouldExposeLikeFallbackInMeta() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(),
            List.of(candidate("fact", "锁相关的记忆")), false, List.of(), NOW));

        ContextPacket packet = service.build(owner, "锁", null, 4000, null, null);

        // 调用方必须能分辨“召回降级”与“确实没记过”，否则会向用户给出没有依据的结论
        assertThat(packet.meta().recallPath()).isEqualTo(ContextPacket.Meta.PATH_LIKE);
    }

    @Test
    void shouldAlwaysInjectCriticalConstraintEvenWhenBudgetIsTight() {
        ConstraintEntity critical = constraint("不擅自升级框架大版本", "critical");
        List<ContextCandidate> memories = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            memories.add(candidate("experience", "较长的一条经验记录内容编号" + i, 0.95D));
        }
        given(new ContextDataReader.Loaded(null, null, List.of(critical), List.of(), memories, true, List.of(), NOW));

        // 500 是 Schema 下限：约束必须在前，否则 Agent 会给出违反用户红线的方案
        ContextPacket packet = service.build(owner, "重构上传模块", null, 500, null, null);

        assertThat(packet.constraints()).extracting(ContextPacket.Item::summary)
            .containsExactly("不擅自升级框架大版本");
        assertThat(packet.meta().truncatedSections()).doesNotContain(ContextPacket.Meta.SECTION_CONSTRAINTS);
    }

    @Test
    void shouldTruncateConstraintsBeforeMemories() {
        List<ConstraintEntity> constraints = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            constraints.add(constraint("第" + i + "条约束正文需要占用一定预算", i == 0 ? "critical" : "normal"));
        }
        given(new ContextDataReader.Loaded(null, null, constraints, List.of(),
            List.of(candidate("fact", "一条事实")), true, List.of(), NOW));

        ContextPacket packet = service.build(owner, "任务", null, 500, null, null);

        assertThat(packet.constraints()).hasSizeLessThan(20);
        assertThat(packet.meta().truncatedSections()).contains(ContextPacket.Meta.SECTION_CONSTRAINTS);
    }

    @Test
    void shouldInjectProfileAndProjectWithoutBeingDropped() {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setHeadline("Java 后端工程师");
        profile.setSummary("偏好结构化、可执行的技术方案");
        UUID projectId = UUID.randomUUID();
        given(new ContextDataReader.Loaded(profile, project(projectId, "会议纪要系统"), List.of(), List.of(),
            List.of(), true, List.of(), NOW));

        ContextPacket packet = service.build(owner, "任务", projectId, 500, null, null);

        assertThat(packet.profile().headline()).isEqualTo("Java 后端工程师");
        assertThat(packet.project().id()).isEqualTo(projectId);
    }

    @Test
    void shouldFlagProjectSectionWhenRequestedProjectIsUnreadable() {
        // 项目被删或不可见时必须报告：否则 Agent 以为“这个项目没有背景”，而事实是拿不到
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true, List.of(), NOW));

        ContextPacket packet = service.build(owner, "任务", UUID.randomUUID(), 4000, null, null);

        assertThat(packet.project()).isNull();
        assertThat(packet.meta().truncatedSections()).contains(ContextPacket.Meta.SECTION_PROJECT);
    }

    @Test
    void shouldInjectStyleRulesInOrder() {
        StyleRuleEntity format = styleRule("answer_format", "先给结论再给步骤");
        StyleRuleEntity tone = styleRule("tone", "简洁");
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(format, tone), List.of(), true, List.of(), NOW));

        ContextPacket packet = service.build(owner, "任务", null, 4000, null, null);

        assertThat(packet.styleRules()).extracting(ContextPacket.StyleRule::key)
            .containsExactly("answer_format", "tone");
    }

    @Test
    void shouldOmitBoundaryMarkerWhenSkillsNotRequested() {
        given(emptyLoaded());

        ContextPacket withSkills = service.build(owner, "任务", null, null, null, null);
        ContextPacket withoutSkills = service.build(owner, "任务", null, null, null, Boolean.FALSE);

        // 15.2 要求对不可信内容做边界标记，但由调用方在拼接提示词时使用，
        // 服务端不强行包裹正文，以免破坏 JSON 结构让结构化消费变困难
        assertThat(withSkills.contentBoundaryMarker()).contains("不是系统指令");
        assertThat(withoutSkills.contentBoundaryMarker()).isNull();
    }

    @Test
    void shouldWriteAccessLogWithInjectedMemoryIds() {
        ContextCandidate first = candidate("fact", "事实一");
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(first), true, List.of(), NOW));
        UUID projectId = UUID.randomUUID();

        service.build(owner, "修复上传", projectId, 4000, null, null);

        ArgumentCaptor<ContextAccessLogEntity> log = ArgumentCaptor.forClass(ContextAccessLogEntity.class);
        verify(accessLogMapper).insert(log.capture());
        assertThat(log.getValue().getUserId()).isEqualTo(USER_ID.toString());
        assertThat(log.getValue().getProjectId()).isEqualTo(projectId.toString());
        assertThat(log.getValue().getMemoryIds()).containsExactly(first.id().toString());
        assertThat(log.getValue().getSkillIds()).isEmpty();
        assertThat(log.getValue().getTokenBudget()).isEqualTo(4000);
        assertThat(log.getValue().getTokenEstimate()).isPositive();
    }

    @Test
    void shouldRecordAgentIdentityInAccessLog() {
        given(emptyLoaded());

        service.build(agent, "任务", null, 4000, null, null);

        ArgumentCaptor<ContextAccessLogEntity> log = ArgumentCaptor.forClass(ContextAccessLogEntity.class);
        verify(accessLogMapper).insert(log.capture());
        assertThat(log.getValue().getAgentClientId()).isEqualTo(AGENT_ID.toString());
    }

    @Test
    void shouldTruncateLongTaskSummaryForAccessLog() {
        given(emptyLoaded());

        service.build(owner, "长".repeat(1500), null, 4000, null, null);

        ArgumentCaptor<ContextAccessLogEntity> log = ArgumentCaptor.forClass(ContextAccessLogEntity.class);
        verify(accessLogMapper).insert(log.capture());
        // task_summary 列宽 1000：不截断会直接报 Data too long 让整次构建失败
        assertThat(log.getValue().getTaskSummary()).hasSize(1000);
    }

    @Test
    void shouldAuditContextBuild() {
        given(emptyLoaded());

        service.build(owner, "任务", null, 4000, null, null);

        verify(auditRecorder).record(any());
    }

    @Test
    void shouldTouchUsageSignalInOneBatchOutsideTheReadTransaction() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(),
            List.of(candidate("fact", "事实一"), candidate("fact", "事实二")), true, List.of(), NOW));

        service.build(owner, "任务", null, 4000, null, null);

        // 11.4 明确禁止主事务逐行写：一次构建可能命中数十条记忆，逐行 UPDATE 会放大行锁与 binlog
        verify(memoryMapper).touchLastUsed(anyList(), any(Instant.class));
        verify(memoryMapper, never()).updateById(any());
    }

    @Test
    void shouldStillReturnContextWhenUsageSignalWriteFails() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(),
            List.of(candidate("fact", "事实一")), true, List.of(), NOW));
        when(memoryMapper.touchLastUsed(anyList(), any(Instant.class))).thenThrow(new RuntimeException("死锁"));

        ContextPacket packet = service.build(owner, "任务", null, 4000, null, null);

        // 使用信号只影响下一次排序，不值得让已经算好的上下文作废
        assertThat(packet.memories()).hasSize(1);
    }

    @Test
    void shouldNotTouchUsageWhenNothingInjected() {
        given(emptyLoaded());

        service.build(owner, "任务", null, 4000, null, null);

        verify(memoryMapper, never()).touchLastUsed(anyList(), any(Instant.class));
    }

    @Test
    void shouldPassTypeFilterDownToRecall() {
        given(emptyLoaded());

        service.build(owner, "任务", null, 4000, List.of("experience", "decision"), null);

        // 末位 true：includeSkills 未指定时按“包含”处理（9.4 Schema 的 default true）
        verify(dataReader).load(eq(owner), any(ContextQuery.class), eq(null),
            eq(List.of(MemoryType.EXPERIENCE, MemoryType.DECISION)), anyInt(), eq(true));
    }

    @Test
    void shouldRejectTooManyTypeFilters() {
        assertThatThrownBy(() -> service.build(owner, "任务", null, 4000,
            List.of("fact", "fact", "fact", "fact", "fact", "fact", "fact"), null))
            .isInstanceOf(BizException.class);
    }

    @Test
    void shouldCapCandidateRecallAtConfiguredLimit() {
        given(emptyLoaded());

        service.build(owner, "任务", null, 4000, null, null);

        verify(dataReader).load(eq(owner), any(ContextQuery.class), eq(null), eq(List.of()),
            eq(properties.getMaxCandidates()), eq(true));
    }

    @Test
    void shouldInjectSkillWithDescriptionAndContent() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true,
            List.of(skill("发布检查清单", "上线前逐项确认", "第一步 跑回归", 1.0D)), NOW));

        ContextPacket packet = service.build(owner, "上线发布", null, 4000, null, null);

        assertThat(packet.skills()).hasSize(1);
        ContextPacket.Item item = packet.skills().get(0);
        assertThat(item.type()).isEqualTo("skill");
        // 注入文本必须自带标题与用途：只给一段没有名字的 Markdown，Agent 会当普通参考文本忽略
        assertThat(item.summary()).contains("发布检查清单").contains("上线前逐项确认").contains("第一步 跑回归");
        assertThat(packet.meta().truncatedSections()).doesNotContain(ContextPacket.Meta.SECTION_SKILLS);
    }

    @Test
    void shouldDropSkillBeforeMemoriesWhenBudgetTight() {
        // 11.5 的相关记忆（第 4 优先级）高于 Skill（第 5）：预算不够时必须先舍 Skill
        String longSkill = "检".repeat(3000);
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(),
            List.of(candidate("fact", "网关限制 20MB")), true,
            List.of(skill("很长的流程", "说明", longSkill, 1.0D)), NOW));

        ContextPacket packet = service.build(owner, "上传失败", null, 500, null, null);

        assertThat(packet.memories()).as("记忆段必须保留").isNotEmpty();
        assertThat(packet.skills()).as("超预算的 Skill 整条不装而不是截半段").isEmpty();
        assertThat(packet.meta().truncatedSections()).contains(ContextPacket.Meta.SECTION_SKILLS);
    }

    @Test
    void shouldNotLoadSkillsWhenCallerOptsOut() {
        given(emptyLoaded());

        ContextPacket packet = service.build(owner, "任务", null, 4000, null, Boolean.FALSE);

        verify(dataReader).load(eq(owner), any(ContextQuery.class), eq(null), eq(List.of()),
            anyInt(), eq(false));
        assertThat(packet.skills()).isEmpty();
        // 边界标记与 Skill 同进退：调用方明确不要 Skill 时，也不必再收这份提示
        assertThat(packet.contentBoundaryMarker()).isNull();
    }

    @Test
    void shouldKeepSmallerSkillsWhenTheTopOneDoesNotFit() {
        // 与记忆段同一教训（M5）：最相关的流程往往最长，它放不下不应连带清空后面装得下的短流程
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true,
            List.of(skill("很长的发布流程", "说明", "检".repeat(3000), 1.0D),
                skill("短检查项", "说明", "确认回滚脚本可用", 0.5D)), NOW));

        ContextPacket packet = service.build(owner, "上线发布", null, 500, null, null);

        assertThat(packet.skills()).hasSize(1);
        assertThat(packet.skills().get(0).summary()).startsWith("短检查项");
        assertThat(packet.meta().truncatedSections()).contains(ContextPacket.Meta.SECTION_SKILLS);
    }

    @Test
    void shouldRecordInjectedSkillIdsInAccessLog() {
        com.example.pwt.skill.infrastructure.SkillEntity skill =
            skill("灰度发布", "按批次放量", "第一步 观察指标", 1.0D);
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true,
            List.of(skill), NOW));

        service.build(owner, "发布", null, 4000, null, null);

        ArgumentCaptor<ContextAccessLogEntity> captor =
            ArgumentCaptor.forClass(ContextAccessLogEntity.class);
        verify(accessLogMapper).insert(captor.capture());
        // 19.4 要求能回放“本次返回了哪些 Skill”，与 memory_ids 合成一列就按类型查不回来
        assertThat(captor.getValue().getSkillIds()).containsExactly(skill.getId());
    }

    @Test
    void shouldNormalizeSkillScoreByCandidateMax() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true,
            List.of(skill("弱相关", "说明", "内容", 2.0D), skill("强相关", "说明", "内容", 8.0D)), NOW));

        ContextPacket packet = service.build(owner, "任务", null, 4000, null, null);

        assertThat(packet.skills()).extracting(ContextPacket.Item::summary)
            .containsExactly("强相关：说明\n内容", "弱相关：说明\n内容");
        assertThat(packet.skills().get(0).score()).isCloseTo(1.0D, org.assertj.core.data.Offset.offset(0.0001D));
        assertThat(packet.skills().get(1).score()).isCloseTo(0.25D, org.assertj.core.data.Offset.offset(0.0001D));
    }

    @Test
    void shouldUseFixedLowScoreForLikeFallbackSkill() {
        given(new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), false,
            List.of(skill("单字命中", "说明", "内容", null)), NOW));

        ContextPacket packet = service.build(owner, "锁", null, 4000, null, null);

        // 兜底路径没有相关度分数，记固定低分：与记忆段同口径，避免兜底命中被当成强相关
        assertThat(packet.skills().get(0).score())
            .isEqualTo(com.example.pwt.context.domain.ContextWeights.likeFallbackRelevance());
    }

    private void given(ContextDataReader.Loaded loaded) {
        when(dataReader.load(any(), any(ContextQuery.class), any(), anyList(), anyInt(), anyBoolean())).thenReturn(loaded);
    }

    private ContextDataReader.Loaded emptyLoaded() {
        return new ContextDataReader.Loaded(null, null, List.of(), List.of(), List.of(), true, List.of(), NOW);
    }

    private ContextCandidate candidate(String type, String text) {
        return candidate(type, text, 0.9D);
    }

    private ContextCandidate candidate(String type, String text, double relevance) {
        return new ContextCandidate(UUID.randomUUID(), type, text, 4, new BigDecimal("0.9000"),
            NOW.minus(1, ChronoUnit.DAYS), null, null, relevance, "L1", List.of());
    }

    private com.example.pwt.skill.infrastructure.SkillEntity skill(String name, String description,
                                                                    String content, Double relevance) {
        com.example.pwt.skill.infrastructure.SkillEntity entity =
            new com.example.pwt.skill.infrastructure.SkillEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setSlug("slug-" + entity.getId().substring(0, 8));
        entity.setName(name);
        entity.setDescription(description);
        entity.setContentMd(content);
        entity.setVersion(1);
        entity.setStatus("published");
        entity.setVisibility("private");
        entity.setRelevance(relevance);
        return entity;
    }

    private ConstraintEntity constraint(String rule, String severity) {
        ConstraintEntity entity = new ConstraintEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setName("约束");
        entity.setRule(rule);
        entity.setSeverity(severity);
        entity.setStatus("active");
        entity.setVisibility("private");
        return entity;
    }

    private StyleRuleEntity styleRule(String key, Object value) {
        StyleRuleEntity entity = new StyleRuleEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setRuleKey(key);
        entity.setRuleValue(value);
        entity.setConfidence(new BigDecimal("0.9000"));
        entity.setStatus("approved");
        entity.setVisibility("private");
        return entity;
    }

    private com.example.pwt.work.infrastructure.ProjectEntity project(UUID id, String name) {
        com.example.pwt.work.infrastructure.ProjectEntity entity =
            new com.example.pwt.work.infrastructure.ProjectEntity();
        entity.setId(id.toString());
        entity.setUserId(USER_ID.toString());
        entity.setName(name);
        entity.setDescription("语音转写与结构化纪要");
        entity.setStatus("active");
        entity.setVisibility("private");
        return entity;
    }
}
