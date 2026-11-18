package com.example.pwt.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.MemoryPolicyProperties;
import com.example.pwt.memory.domain.ApprovalPolicy;
import com.example.pwt.memory.domain.MemoryStatus;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.domain.ReviewDecision;
import com.example.pwt.memory.domain.RiskLevel;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceMapper;
import com.example.pwt.memory.infrastructure.MemoryMapper;
import com.example.pwt.memory.infrastructure.MemoryReviewEntity;
import com.example.pwt.memory.infrastructure.MemoryReviewMapper;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
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
 * 记忆服务的领域规则测试（设计文档 6.2、6.3、9.6、10.4，验收用例 5）。
 *
 * <p>不依赖数据库：判定都在触达持久层之前完成或被 mock 隔离，
 * 索引与约束层面的行为由 {@code MysqlDdlMigrationTest} 覆盖。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private MemoryMapper memoryMapper;

    @Mock
    private MemoryEvidenceMapper evidenceMapper;

    @Mock
    private MemoryReviewMapper reviewMapper;

    @Mock
    private WorkLogMapper workLogMapper;

    @Mock
    private AuditRecorder auditRecorder;

    private MemoryService service;

    private ActorContext owner;

    private ActorContext agent;

    @BeforeEach
    void setUp() {
        service = new MemoryService(memoryMapper, evidenceMapper, reviewMapper, workLogMapper,
            new ApprovalPolicy(new MemoryPolicyProperties(true, RiskLevel.L1, 0.85d, 2)), auditRecorder);
        owner = ActorContext.owner(USER_ID);
        agent = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.MEMORY_PROPOSE, Scope.MEMORY_READ));
        lenient().when(memoryMapper.selectDuplicates(anyString(), anyString(), anyString(), any()))
            .thenReturn(List.of());
        lenient().when(memoryMapper.selectSimilar(anyString(), anyString(), anyString(), any(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        lenient().when(memoryMapper.insert(any(MemoryEntity.class))).thenReturn(1);
    }

    @Test
    void ownerDeclaredFactShouldBeApprovedImmediately() {
        MemoryEntity saved = service.propose(owner, command(MemoryType.FACT, "项目使用 Spring Boot 3.2"));

        assertThat(saved.getStatus()).isEqualTo(MemoryStatus.APPROVED.getValue());
        assertThat(saved.getSourceType()).isEqualTo("manual");
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.L0.name());
        assertThat(saved.getNormalizedHash()).hasSize(64);
        assertThat(saved.getVersion()).isEqualTo(1);
    }

    @Test
    void agentCandidateShouldStayPendingWithReviewTrail() {
        service.propose(agent, command(MemoryType.FACT, "推断出的结论"));

        ArgumentCaptor<MemoryEntity> saved = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MemoryStatus.CANDIDATE.getValue());
        assertThat(saved.getValue().getSourceType()).isEqualTo("agent");

        ArgumentCaptor<MemoryReviewEntity> review = ArgumentCaptor.forClass(MemoryReviewEntity.class);
        verify(reviewMapper).insert(review.capture());
        assertThat(review.getValue().getDecision()).isEqualTo(ReviewDecision.PENDING_REVIEW.getValue());
        assertThat(review.getValue().getReviewerType()).isEqualTo("system");
        // 16.3：界面要展示“系统判断原因”
        assertThat(review.getValue().getReason()).isNotBlank();
    }

    @Test
    void credentialContentShouldBeRejectedAndRedacted() {
        MemoryEntity saved = service.propose(owner,
            command(MemoryType.FACT, "登录口令 password = SuperSecret123"));

        assertThat(saved.getStatus()).isEqualTo(MemoryStatus.REJECTED.getValue());
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.L3.name());
        assertThat(saved.getContent()).doesNotContain("SuperSecret123");
        // 去重键也不落，避免低熵口令被字典碰撞反推
        assertThat(saved.getNormalizedHash()).isNull();
    }

    @Test
    void duplicateContentShouldAccumulateEvidenceInsteadOfInserting() {
        MemoryEntity existing = existing(MemoryType.FACT, MemoryStatus.CANDIDATE);
        when(memoryMapper.selectDuplicates(anyString(), anyString(), anyString(), any()))
            .thenReturn(List.of(existing));
        when(evidenceMapper.countByMemory(existing.getId())).thenReturn(2L);

        MemoryEntity result = service.propose(agent, command(MemoryType.FACT, "项目使用 Spring Boot 3.2"));

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(memoryMapper, never()).insert(any(MemoryEntity.class));
        verify(evidenceMapper).insert(any());
    }

    @Test
    void duplicateOfApprovedMemoryShouldOnlyAddEvidence() {
        MemoryEntity existing = existing(MemoryType.FACT, MemoryStatus.APPROVED);
        when(memoryMapper.selectDuplicates(anyString(), anyString(), anyString(), any()))
            .thenReturn(List.of(existing));
        when(evidenceMapper.countByMemory(existing.getId())).thenReturn(3L);

        MemoryEntity result = service.propose(agent, command(MemoryType.FACT, "项目使用 Spring Boot 3.2"));

        assertThat(result.getStatus()).isEqualTo(MemoryStatus.APPROVED.getValue());
        verify(memoryMapper, never()).updateById(any(MemoryEntity.class));
    }

    @Test
    void conflictWithExistingShouldForceHumanReview() {
        MemoryEntity similar = existing(MemoryType.FACT, MemoryStatus.APPROVED);
        similar.setContent("项目框架是 spring boot 3.1");
        when(memoryMapper.selectSimilar(anyString(), anyString(), anyString(), any(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(similar));

        // 带上证据，避免先被“缺少来源证据”规则拦下而测不到冲突分支
        MemoryProposeCommand command = new MemoryProposeCommand(MemoryType.FACT.getValue(),
            "项目框架是 spring boot 3.2", null, "spring boot 3.2", null, null, 0.9d, null, Map.of());
        MemoryEntity saved = service.propose(agent, command);

        assertThat(saved.getStatus()).isEqualTo(MemoryStatus.CANDIDATE.getValue());
        ArgumentCaptor<MemoryReviewEntity> review = ArgumentCaptor.forClass(MemoryReviewEntity.class);
        verify(reviewMapper).insert(review.capture());
        assertThat(review.getValue().getReason()).contains("冲突");
        assertThat(review.getValue().getRuleSnapshot()).containsEntry("conflictCount", 1);
    }

    @Test
    void agentMustNotApproveOrReject() {
        assertThatThrownBy(() -> service.approve(agent, UUID.randomUUID(), null))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.reject(agent, UUID.randomUUID(), "x"))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.archive(agent, UUID.randomUUID()))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.revise(agent, UUID.randomUUID(), "新内容", null, true))
            .isInstanceOf(BizException.class);
    }

    @Test
    void agentShouldNeedProposeScope() {
        ActorContext reader = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.MEMORY_READ));

        assertThatThrownBy(() -> service.propose(reader, command(MemoryType.FACT, "内容")))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCOPE_REQUIRED);
    }

    @Test
    void onlyOwnerMayProposePublicVisibility() {
        assertThatThrownBy(() -> service.propose(agent,
            command(MemoryType.FACT, "内容", Visibility.PUBLIC.getValue())))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("需由 Owner 在网站显式发布");

        assertThatCode(() -> service.propose(owner,
            command(MemoryType.FACT, "公开事实", Visibility.PUBLIC.getValue())))
            .doesNotThrowAnyException();
    }

    @Test
    void proposedMemoryShouldDefaultToPrivate() {
        MemoryEntity saved = service.propose(agent, command(MemoryType.FACT, "内容"));

        assertThat(saved.getVisibility()).isEqualTo(Visibility.PRIVATE.getValue());
    }

    @Test
    void unknownSourceTypeOrTypeShouldBeRejectedBeforeInsert() {
        MemoryProposeCommand bad = new MemoryProposeCommand("rumour", "内容", null, null, null,
            null, null, null, Map.of());

        assertThatThrownBy(() -> service.propose(owner, bad))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR)
            .hasMessageContaining("记忆类型");
        verify(memoryMapper, never()).insert(any(MemoryEntity.class));
    }

    @Test
    void blankOrOverlongContentShouldBeRejected() {
        assertThatThrownBy(() -> service.propose(owner, command(MemoryType.FACT, "   ")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("正文");
        assertThatThrownBy(() -> service.propose(owner, command(MemoryType.FACT, "长".repeat(10001))))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("10000");
    }

    @Test
    void foreignSourceWorkLogShouldBeRejected() {
        UUID workLogId = UUID.randomUUID();
        WorkLogEntity foreign = new WorkLogEntity();
        foreign.setId(workLogId.toString());
        foreign.setUserId(UUID.randomUUID().toString());
        when(workLogMapper.selectById(workLogId.toString())).thenReturn(foreign);

        // 跨用户统一按“不存在”处理，不泄露资源存在性
        assertThatThrownBy(() -> service.propose(owner,
            new MemoryProposeCommand("fact", "内容", null, null, workLogId, null, null, null, Map.of())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void missingSourceWorkLogShouldBeRejectedAsBadInput() {
        UUID missing = UUID.randomUUID();
        when(workLogMapper.selectById(missing.toString())).thenReturn(null);

        assertThatThrownBy(() -> service.propose(owner,
            new MemoryProposeCommand("fact", "内容", null, null, missing, null, null, null, Map.of())))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("来源工作记录不存在");
    }

    @Test
    void approvingSupersededMemoryShouldBeRejected() {
        MemoryEntity entity = existing(MemoryType.FACT, MemoryStatus.SUPERSEDED);
        when(memoryMapper.selectForUpdate(entity.getId())).thenReturn(entity);

        assertThatThrownBy(() -> service.approve(owner, UUID.fromString(entity.getId()), "注释"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不可批准");
    }

    @Test
    void approvingCandidateShouldWriteOwnerReviewAndBeIdempotent() {
        MemoryEntity entity = existing(MemoryType.FACT, MemoryStatus.CANDIDATE);
        when(memoryMapper.selectForUpdate(entity.getId())).thenReturn(entity);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);
        UUID id = UUID.fromString(entity.getId());

        assertThat(service.approve(owner, id, "已确认").getStatus())
            .isEqualTo(MemoryStatus.APPROVED.getValue());
        // 重复批准是空操作，不报错也不重复留痕
        assertThat(service.approve(owner, id, null).getStatus())
            .isEqualTo(MemoryStatus.APPROVED.getValue());

        ArgumentCaptor<MemoryReviewEntity> review = ArgumentCaptor.forClass(MemoryReviewEntity.class);
        verify(reviewMapper).insert(review.capture());
        assertThat(review.getValue().getReviewerType()).isEqualTo("owner");
        assertThat(review.getValue().getReason()).isEqualTo("已确认");
    }

    @Test
    void revisingApprovedMemoryShouldCreateNewVersionAndSupersedeOld() {
        MemoryEntity entity = existing(MemoryType.FACT, MemoryStatus.APPROVED);
        entity.setVersion(1);
        when(memoryMapper.selectForUpdate(entity.getId())).thenReturn(entity);
        when(memoryMapper.insert(any(MemoryEntity.class))).thenReturn(1);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);
        when(evidenceMapper.selectByMemory(entity.getId())).thenReturn(List.of());

        MemoryEntity revised = service.revise(owner, UUID.fromString(entity.getId()),
            "项目使用 Spring Boot 3.3", null, true);

        assertThat(revised.getVersion()).isEqualTo(2);
        assertThat(revised.getSupersedesId()).isEqualTo(entity.getId());
        assertThat(revised.getStatus()).isEqualTo(MemoryStatus.APPROVED.getValue());
        assertThat(entity.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED.getValue());
        assertThat(revised.getNormalizedHash())
            .isNotEqualTo(com.example.pwt.memory.domain.MemoryContent
                .normalizedHash(MemoryType.FACT, "项目使用 spring boot 3.2"));
    }

    @Test
    void revisingCandidateShouldStayInPlace() {
        MemoryEntity entity = existing(MemoryType.FACT, MemoryStatus.CANDIDATE);
        when(memoryMapper.selectForUpdate(entity.getId())).thenReturn(entity);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);

        MemoryEntity revised = service.revise(owner, UUID.fromString(entity.getId()), "修正后的内容", "摘要", false);

        assertThat(revised.getId()).isEqualTo(entity.getId());
        assertThat(revised.getStatus()).isEqualTo(MemoryStatus.CANDIDATE.getValue());
        assertThat(revised.getContent()).isEqualTo("修正后的内容");
    }

    @Test
    void supersedeShouldApproveReplacementAndRetireOld() {
        MemoryEntity old = existing(MemoryType.FACT, MemoryStatus.APPROVED);
        MemoryEntity next = existing(MemoryType.FACT, MemoryStatus.CANDIDATE);
        when(memoryMapper.selectForUpdate(old.getId())).thenReturn(old);
        when(memoryMapper.selectForUpdate(next.getId())).thenReturn(next);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);

        MemoryEntity result = service.supersede(owner, UUID.fromString(old.getId()),
            UUID.fromString(next.getId()), "取值已更新");

        assertThat(result.getStatus()).isEqualTo(MemoryStatus.APPROVED.getValue());
        assertThat(result.getSupersedesId()).isEqualTo(old.getId());
        assertThat(old.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED.getValue());
    }

    @Test
    void keywordOfShouldBoundRecallPhrase() {
        assertThat(MemoryService.keywordOf("  项目框架是   SPRING BOOT 3.2 且已上线 ")).hasSizeLessThanOrEqualTo(24);
        assertThat(MemoryService.keywordOf(null)).isEmpty();
    }

    @Test
    void supportedBySourceShouldUseContainmentNotModelClaim() {
        WorkLogEntity source = new WorkLogEntity();
        source.setTitle("Feign 上传问题排查");
        source.setContent("上传 MultipartFile 时必须用 RequestPart 注解");

        assertThat(MemoryService.supportedBySource("要用 RequestPart", "必须用 RequestPart", source)).isTrue();
        assertThat(MemoryService.supportedBySource("要用 RequestPart", "原文没有这句话", source)).isFalse();
        assertThat(MemoryService.supportedBySource("要用 RequestPart", null, null)).isFalse();
    }

    @Test
    void pendingQueueShouldBeOwnerOnly() {
        // 设计文档 10.2 把 /memories/pending 标为 Owner 专属：审核队列是治理入口
        assertThatThrownBy(() -> service.listPending(agent,
            PageQuery.of(1, 20)))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void singleWorkLogShouldNotCountAsRepeatedEvidence() {
        // 来源记录与随附摘录是同一条证据：单次提交不得凑满 6.3 第 3 条的“多次证据”
        UUID workLogId = UUID.randomUUID();
        WorkLogEntity source = new WorkLogEntity();
        source.setId(workLogId.toString());
        source.setUserId(USER_ID.toString());
        source.setContent("多次遇到上传要用 RequestPart 的情况");
        when(workLogMapper.selectById(workLogId.toString())).thenReturn(source);

        MemoryProposeCommand command = new MemoryProposeCommand(MemoryType.EXPERIENCE.getValue(),
            "上传 MultipartFile 要用 RequestPart", null, "上传要用 RequestPart", workLogId, null, 0.95d, null,
            Map.of());

        assertThat(service.propose(agent, command).getStatus())
            .as("单次证据的经验应待人工审核")
            .isEqualTo(MemoryStatus.CANDIDATE.getValue());
    }

    @Test
    void repeatedSubmissionsShouldAccumulateTowardsAutoApproval() {
        // 同一经验被第二次提交：证据累计到达 preferenceMinEvidence 后，
        // 原本待审的候选应被自动批准，而不是永远滞留在审核队列里
        MemoryEntity existing = existing(MemoryType.EXPERIENCE, MemoryStatus.CANDIDATE);
        existing.setContent("上传 MultipartFile 要用 RequestPart");
        existing.setConfidence(java.math.BigDecimal.valueOf(0.95d));
        when(memoryMapper.selectDuplicates(anyString(), anyString(), anyString(), any()))
            .thenReturn(List.of(existing));
        when(evidenceMapper.countByMemory(existing.getId())).thenReturn(2L);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);
        UUID workLogId = UUID.randomUUID();
        WorkLogEntity source = new WorkLogEntity();
        source.setId(workLogId.toString());
        source.setUserId(USER_ID.toString());
        source.setContent("上传 MultipartFile 时必须用 RequestPart 注解");
        when(workLogMapper.selectById(workLogId.toString())).thenReturn(source);

        MemoryProposeCommand command = new MemoryProposeCommand(MemoryType.EXPERIENCE.getValue(),
            "上传 MultipartFile 要用 RequestPart", null, "必须用 RequestPart", workLogId, null, 0.95d, null,
            Map.of());

        MemoryEntity result = service.propose(agent, command);

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getStatus()).isEqualTo(MemoryStatus.APPROVED.getValue());
        verify(evidenceMapper).insert(any());
        verify(memoryMapper, never()).insert(any(MemoryEntity.class));
    }

    private static MemoryProposeCommand command(MemoryType type, String content) {
        return command(type, content, null);
    }

    @Test
    void shouldInheritProjectFromSourceWorkLog() {
        UUID projectId = UUID.randomUUID();
        UUID workLogId = UUID.randomUUID();
        WorkLogEntity source = new WorkLogEntity();
        source.setId(workLogId.toString());
        source.setUserId(USER_ID.toString());
        source.setProjectId(projectId.toString());
        source.setContent("网关超时需要在预发环境单独验证");
        when(workLogMapper.selectById(workLogId.toString())).thenReturn(source);
        // 在 insert 的调用时刻取值，而不是抓引用后再读：实体是可变对象，
        // 落库之后才补字段用引用断言是看不出来的（端到端就是这样漏掉过）
        List<String> projectedAtInsert = new ArrayList<>();
        doAnswer(invocation -> {
            projectedAtInsert.add(((MemoryEntity) invocation.getArgument(0)).getProjectId());
            return 1;
        }).when(memoryMapper).insert(any(MemoryEntity.class));

        service.propose(agent, new MemoryProposeCommand(MemoryType.EXPERIENCE.getValue(),
            "网关超时需要在预发环境单独验证后再改配置", null, "预发环境单独验证", workLogId, null, 0.9d, null,
            Map.of()));

        // 记忆与项目的关系是推导出来的：只有来源工作记录知道它属于哪个项目。
        // 不在写入端落列，按项目检索就得每次走证据两跳（V10 迁移说明）
        assertThat(projectedAtInsert).containsExactly(projectId.toString());
    }

    @Test
    void shouldBackfillProjectWhenDuplicateGainsASourcedEvidence() {
        UUID projectId = UUID.randomUUID();
        MemoryEntity existing = existing(MemoryType.EXPERIENCE, MemoryStatus.APPROVED);
        existing.setProjectId(null);
        when(memoryMapper.selectDuplicates(anyString(), anyString(), anyString(), any()))
            .thenReturn(List.of(existing));
        when(evidenceMapper.countByMemory(existing.getId())).thenReturn(2L);
        when(memoryMapper.updateById(any(MemoryEntity.class))).thenReturn(1);
        UUID workLogId = UUID.randomUUID();
        WorkLogEntity source = new WorkLogEntity();
        source.setId(workLogId.toString());
        source.setUserId(USER_ID.toString());
        source.setProjectId(projectId.toString());
        source.setContent("项目使用 spring boot 3.2");
        when(workLogMapper.selectById(workLogId.toString())).thenReturn(source);

        MemoryEntity result = service.propose(agent, new MemoryProposeCommand(
            MemoryType.EXPERIENCE.getValue(), "项目使用 Spring Boot 3.2", null, "spring boot 3.2", workLogId,
            null, 0.9d, null, Map.of()));

        // 既有记忆来自人工提交时没有项目归属，若不在这里补齐，它在此后的按项目检索里始终缺席
        assertThat(result.getProjectId()).isEqualTo(projectId.toString());
        verify(memoryMapper).updateById(existing);
    }

    private static MemoryProposeCommand command(MemoryType type, String content, String visibility) {
        return new MemoryProposeCommand(type.getValue(), content, null, null, null, visibility, null, null,
            Map.of());
    }

    private static MemoryEntity existing(MemoryType type, MemoryStatus status) {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setMemoryType(type.getValue());
        entity.setContent("项目使用 spring boot 3.2");
        entity.setNormalizedHash("hash");
        entity.setStatus(status.getValue());
        entity.setVisibility(Visibility.PRIVATE.getValue());
        entity.setRiskLevel(RiskLevel.L1.name());
        entity.setSourceType("agent");
        entity.setVersion(1);
        return entity;
    }
}
