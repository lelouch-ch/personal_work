package com.example.pwt.work.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.task.application.TaskService;
import com.example.pwt.task.domain.TaskStatus;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.work.domain.ExtractionState;
import com.example.pwt.work.infrastructure.ProjectMapper;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 工作记录服务的领域规则测试。
 *
 * <p>不依赖数据库：校验失败、可见性判定与标签归一都在触达持久层之前发生或被 mock 隔离，
 * 索引与约束层面的行为由 {@code MysqlDdlMigrationTest} 覆盖。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class WorkLogServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private WorkLogMapper workLogMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private AuditRecorder auditRecorder;

    @Mock
    private TaskService taskService;

    private WorkLogService service;

    private ActorContext owner;

    private ActorContext agent;

    @BeforeEach
    void setUp() {
        service = new WorkLogService(workLogMapper, projectMapper, auditRecorder, taskService);
        owner = ActorContext.owner(USER_ID);
        agent = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.WORK_READ, Scope.WORK_WRITE));
        // 建档后总会排队提取任务，给一个默认返回，让不关心该行为的用例保持原样；
        // 用 lenient 是因为只校验参数非法的用例不会走到入队。
        lenient().when(taskService.enqueue(any(), any(), any(), any())).thenReturn(pendingTask());
    }

    @Test
    void shouldLowercaseTrimAndDeduplicateTags() {
        assertThat(WorkLogService.normalizeTags(java.util.Arrays.asList(" ASR ", "asr", "LLM", null, "  ")))
            .containsExactly("asr", "llm");
    }

    @Test
    void shouldReturnEmptyTagListWhenInputMissing() {
        assertThat(WorkLogService.normalizeTags(null)).isEmpty();
        assertThat(WorkLogService.normalizeTags(List.of())).isEmpty();
    }

    @Test
    void shouldDefaultToPrivateVisibilityForAgent() {
        when(workLogMapper.insert(any(WorkLogEntity.class))).thenReturn(1);

        WorkLogEntity saved = service.create(owner, command(null, null)).entity();

        assertThat(saved.getVisibility()).isEqualTo("private");
        assertThat(saved.getWorkType()).isEqualTo("general");
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getSourceType()).isEqualTo("manual");
        assertThat(saved.getUserId()).isEqualTo(USER_ID.toString());
    }

    @Test
    void shouldMarkAgentSubmissionsWithAgentSource() {
        when(workLogMapper.insert(any(WorkLogEntity.class))).thenReturn(1);

        WorkLogEntity saved = service.create(agent, command(null, null)).entity();

        assertThat(saved.getSourceType()).isEqualTo("agent");
        assertThat(saved.getAgentClientId()).isEqualTo(AGENT_ID.toString());
    }

    @Test
    void shouldRejectPublicVisibilityFromAgent() {
        assertThatThrownBy(() -> service.create(agent, command("public", null)))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        verify(workLogMapper, never()).insert(any(WorkLogEntity.class));
    }

    @Test
    void shouldRejectIllegalWorkTypeBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.create(owner, command(null, "sleeping")))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        verify(workLogMapper, never()).insert(any(WorkLogEntity.class));
    }

    @Test
    void shouldRejectBlankTitle() {
        WorkLogCommand blank = new WorkLogCommand(null, "   ", null, null, null, Instant.now(),
            List.of(), null, null);

        assertThatThrownBy(() -> service.create(owner, blank))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("标题");
    }

    @Test
    void shouldRejectMissingOccurredAt() {
        WorkLogCommand noTime = new WorkLogCommand(null, "标题", null, null, null, null,
            List.of(), null, null);

        assertThatThrownBy(() -> service.create(owner, noTime))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("发生时间");
    }

    @Test
    void shouldRejectTagCountAboveLimit() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 21)
            .mapToObj(index -> "tag" + index).toList();

        assertThatThrownBy(() -> service.create(owner, new WorkLogCommand(null, "标题", null, null, null,
            Instant.now(), tooMany, null, null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("标签数量");
    }

    @Test
    void shouldRequireWorkWriteScopeForAgent() {
        ActorContext readOnly = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.WORK_READ));

        assertThatThrownBy(() -> service.create(readOnly, command(null, null)))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCOPE_REQUIRED);
    }

    @Test
    void shouldNotExposePrivateLogsToAgentSearch() {
        WorkLogEntity privateLog = entity("private");
        WorkLogEntity agentLog = entity("agent");
        when(workLogMapper.selectByTag(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of(privateLog, agentLog));

        assertThat(service.searchByTag(agent, "ASR", 10)).containsExactly(agentLog);
    }

    @Test
    void shouldSeeAllLogsWhenOwnerSearchesByTag() {
        WorkLogEntity privateLog = entity("private");
        WorkLogEntity agentLog = entity("agent");
        when(workLogMapper.selectByTag(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of(privateLog, agentLog));

        assertThat(service.searchByTag(owner, "ASR", 10)).containsExactly(privateLog, agentLog);
    }

    @Test
    void shouldNormalizeTagCaseOnQuerySide() {
        when(workLogMapper.selectByTag(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        service.searchByTag(owner, "  ASR  ", 10);

        verify(workLogMapper).selectByTag(org.mockito.ArgumentMatchers.eq("asr"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(10));
    }

    @Test
    void shouldCapSearchLimitAtFifty() {
        when(workLogMapper.searchFullText(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        service.searchByKeyword(owner, "上传", 500);

        verify(workLogMapper).searchFullText(org.mockito.ArgumentMatchers.eq("上传"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(50));
    }

    @Test
    void shouldRejectBlankKeywordSearch() {
        assertThatThrownBy(() -> service.searchByKeyword(owner, " ", 10))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("关键词");
    }

    @Test
    void shouldQueueExtractionTaskOnCreate() {
        when(workLogMapper.insert(any(WorkLogEntity.class))).thenReturn(1);
        when(taskService.enqueue(any(), any(), any(), any())).thenReturn(pendingTask());

        WorkLogCreation creation = service.create(owner, command(null, null));

        assertThat(creation.extraction()).isEqualTo(ExtractionState.QUEUED);
        verify(taskService).enqueue(org.mockito.ArgumentMatchers.eq(USER_ID),
            org.mockito.ArgumentMatchers.eq(TaskType.EXTRACT_MEMORY), any(), any());
    }

    @Test
    void shouldNotQueueTwiceWhenIdempotencyHits() {
        WorkLogEntity existing = entity("private");
        when(workLogMapper.selectOne(any())).thenReturn(existing);
        when(taskService.find(USER_ID, TaskType.EXTRACT_MEMORY, existing.getId())).thenReturn(pendingTask());

        WorkLogCommand keyed = new WorkLogCommand(null, "标题", null, null, null, Instant.now(),
            List.of(), null, "same-key");
        WorkLogCreation creation = service.create(owner, keyed);

        assertThat(creation.entity()).isSameAs(existing);
        // 已有任务处于排队中，响应应如实报 queued 而不是 notQueued
        assertThat(creation.extraction()).isEqualTo(ExtractionState.QUEUED);
        verify(taskService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void shouldReportSucceededWhenTaskAlreadyDone() {
        WorkLogEntity existing = entity("private");
        AsyncTaskEntity done = pendingTask();
        done.setStatus(TaskStatus.SUCCEEDED.getValue());
        when(workLogMapper.selectOne(any())).thenReturn(existing);
        when(taskService.find(USER_ID, TaskType.EXTRACT_MEMORY, existing.getId())).thenReturn(done);

        WorkLogCommand keyed = new WorkLogCommand(null, "标题", null, null, null, Instant.now(),
            List.of(), null, "same-key");

        assertThat(service.create(owner, keyed).extraction()).isEqualTo(ExtractionState.SUCCEEDED);
    }

    private static AsyncTaskEntity pendingTask() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(USER_ID.toString());
        task.setTaskType(TaskType.EXTRACT_MEMORY.getValue());
        task.setStatus(TaskStatus.PENDING.getValue());
        return task;
    }

    private static WorkLogCommand command(String visibility, String workType) {
        return new WorkLogCommand(null, "完成上传链路排查", "摘要", "正文", workType, Instant.now(),
            List.of("ASR"), visibility, null);
    }

    private static WorkLogEntity entity(String visibility) {
        WorkLogEntity entity = new WorkLogEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setVisibility(visibility);
        entity.setStatus("active");
        return entity;
    }
}
