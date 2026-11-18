package com.example.pwt.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.pwt.common.error.BizException;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import com.example.pwt.task.TaskProperties;
import com.example.pwt.task.domain.TaskPayload;
import com.example.pwt.task.domain.TaskStatus;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.task.infrastructure.AsyncTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Worker 的领取、上下文绑定、重试与租约回收测试（设计文档 17.2、19.2 用例 14）。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskWorkerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    private TaskProperties properties;

    private TaskClaimService claimService;

    @BeforeEach
    void setUp() {
        properties = new TaskProperties(true, Duration.ofSeconds(1), 5, Duration.ofMinutes(5),
            Duration.ofSeconds(30), Duration.ofSeconds(30), 3);
        claimService = new TaskClaimService(asyncTaskMapper, properties);
    }

    @Test
    void claimShouldSkipUpdateWhenNothingPicked() {
        when(asyncTaskMapper.selectClaimable(anyInt())).thenReturn(List.of());

        assertThat(claimService.claim("worker-1", 5)).isEmpty();
        verify(asyncTaskMapper, never()).markRunning(any(), anyString());
    }

    @Test
    void claimShouldMarkPickedTasksRunning() {
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        when(asyncTaskMapper.selectClaimable(5)).thenReturn(List.of(task));
        when(asyncTaskMapper.markRunning(List.of(task.getId()), "worker-1")).thenReturn(1);

        assertThat(claimService.claim("worker-1", 5)).containsExactly(task);
        verify(asyncTaskMapper).markRunning(List.of(task.getId()), "worker-1");
    }

    @Test
    void exhaustedRetriesShouldMarkFailedInsteadOfPending() {
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        task.setRetryCount(2);
        task.setMaxRetries(3);
        claimService.fail(task.getId(), "boom", 2, 3);

        verify(asyncTaskMapper).markRetryOrFailed(eq(task.getId()), eq("boom"), any(Instant.class), eq(true));
    }

    @Test
    void retriesBelowLimitShouldStayPending() {
        claimService.fail("t1", "boom", 0, 3);

        verify(asyncTaskMapper).markRetryOrFailed(eq("t1"), eq("boom"), any(Instant.class), eq(false));
    }

    @Test
    void handlerShouldSeeOwnerScopedSystemActor() {
        AtomicReference<ActorContext> seen = new AtomicReference<>();
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.EXTRACT_MEMORY;
            }

            @Override
            public void handle(AsyncTaskEntity task) {
                seen.set(ActorContextHolder.get());
            }
        };
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        when(asyncTaskMapper.selectClaimable(anyInt())).thenReturn(List.of(task));

        new AsyncTaskWorker(claimService, properties, List.of(handler)).poll();

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().isSystem()).isTrue();
        assertThat(seen.get().userId()).isEqualTo(USER_ID);
        // 处理完必须清理，避免调度线程复用导致用户串线
        assertThat(ActorContextHolder.get()).isNull();
    }

    @Test
    void handlerFailureShouldBeRetriedAndContextStillCleared() {
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        when(asyncTaskMapper.selectClaimable(anyInt())).thenReturn(List.of(task));
        when(asyncTaskMapper.markRetryOrFailed(anyString(), anyString(), any(Instant.class), anyBoolean()))
            .thenReturn(1);
        TaskHandler failing = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.EXTRACT_MEMORY;
            }

            @Override
            public void handle(AsyncTaskEntity t) {
                throw new IllegalStateException("LLM 超时");
            }
        };

        new AsyncTaskWorker(claimService, properties, List.of(failing)).poll();

        verify(asyncTaskMapper).markRetryOrFailed(eq(task.getId()), contains("LLM 超时"),
            any(Instant.class), eq(false));
        verify(asyncTaskMapper, never()).markSucceeded(anyString());
        assertThat(ActorContextHolder.get()).isNull();
    }

    @Test
    void successShouldMarkSucceeded() {
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        when(asyncTaskMapper.selectClaimable(anyInt())).thenReturn(List.of(task));

        new AsyncTaskWorker(claimService, properties, List.of(noopHandler())).poll();

        verify(asyncTaskMapper).markSucceeded(task.getId());
    }

    @Test
    void unknownTaskTypeShouldFailFastWithoutHandler() {
        AsyncTaskEntity task = task(TaskType.EXTRACT_MEMORY);
        task.setTaskType("GENERATE_TREND_REPORT");
        task.setMaxRetries(1);
        when(asyncTaskMapper.selectClaimable(anyInt())).thenReturn(List.of(task));
        when(asyncTaskMapper.markRetryOrFailed(anyString(), anyString(), any(Instant.class), anyBoolean()))
            .thenReturn(1);

        new AsyncTaskWorker(claimService, properties, List.of(noopHandler())).poll();

        // 没有处理器时无需重试，直接按耗尽处理
        verify(asyncTaskMapper).markRetryOrFailed(eq(task.getId()), contains("No handler"),
            any(Instant.class), eq(true));
    }

    @Test
    void duplicatedHandlerForSameTypeShouldBeRejectedAtStartup() {
        assertThatThrownBy(() -> new AsyncTaskWorker(claimService, properties,
            List.of(noopHandler(), noopHandler())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicated TaskHandler");
    }

    @Test
    void disabledWorkerShouldNotTouchDatabase() {
        TaskProperties off = new TaskProperties(false, Duration.ofSeconds(1), 5, Duration.ofMinutes(5),
            Duration.ofSeconds(30), Duration.ofSeconds(30), 3);

        AsyncTaskWorker worker = new AsyncTaskWorker(claimService, off, List.of(noopHandler()));
        worker.poll();
        worker.recover();

        verifyNoInteractions(asyncTaskMapper);
    }

    @Test
    void payloadContractShouldRoundTripWorkLogId() {
        UUID workLogId = UUID.randomUUID();

        assertThat(TaskPayload.requireWorkLogId(TaskPayload.ofWorkLog(workLogId))).isEqualTo(workLogId);
        assertThatThrownBy(() -> TaskPayload.requireWorkLogId(Map.of()))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> TaskPayload.requireWorkLogId(Map.of(TaskPayload.WORK_LOG_ID, "xx")))
            .isInstanceOf(BizException.class);
    }

    @Test
    void defaultsShouldBeFilledForMissingConfig() {
        TaskProperties defaults = new TaskProperties(true, null, 0, null, null, null, 0);

        assertThat(defaults.pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.batchSize()).isEqualTo(10);
        assertThat(defaults.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.backoffBase()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.maxRetryAttempts()).isEqualTo(5);
    }

    private static TaskHandler noopHandler() {
        return new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.EXTRACT_MEMORY;
            }

            @Override
            public void handle(AsyncTaskEntity task) {
                // 空实现
            }
        };
    }

    private static AsyncTaskEntity task(TaskType type) {
        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setTaskType(type.getValue());
        entity.setStatus(TaskStatus.PENDING.getValue());
        entity.setRetryCount(0);
        entity.setMaxRetries(3);
        entity.setPayload(TaskPayload.ofWorkLog(UUID.randomUUID()));
        return entity;
    }
}
