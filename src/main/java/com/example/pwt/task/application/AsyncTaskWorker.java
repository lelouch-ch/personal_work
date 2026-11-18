package com.example.pwt.task.application;

import com.example.pwt.common.util.Masking;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import com.example.pwt.task.TaskProperties;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 异步任务 Worker（设计文档 17.2、18.1）。
 *
 * <p>与 REST 同进程部署，靠 {@code FOR UPDATE SKIP LOCKED} 支持未来横向扩容而不重复消费。
 *
 * <p>事务边界：领取是独立的短事务，业务处理在事务提交之后执行，成功/失败各自开新事务落库。
 * 这样 LLM 调用等耗时操作不会长时间持有任务行锁。
 *
 * <p>处理每个任务前绑定该任务所属用户的系统主体上下文，使多租户拦截器照常生效，
 * 结束后必须清理，避免调度线程复用导致用户上下文串线。
 *
 * @author pwt
 */
@Component
public class AsyncTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWorker.class);

    private final TaskClaimService claimService;

    private final TaskProperties properties;

    private final Map<TaskType, TaskHandler> handlers;

    private final String workerId;

    /**
     * 构造 Worker。
     *
     * @param claimService 领取事务服务
     * @param properties   任务配置
     * @param handlerList  各领域注册的任务处理器
     */
    public AsyncTaskWorker(TaskClaimService claimService, TaskProperties properties,
                           List<TaskHandler> handlerList) {
        this.claimService = claimService;
        this.properties = properties;
        this.handlers = new EnumMap<>(TaskType.class);
        for (TaskHandler handler : handlerList) {
            TaskHandler previous = this.handlers.putIfAbsent(handler.taskType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicated TaskHandler for " + handler.taskType());
            }
        }
        this.workerId = "worker-" + ManagementFactory.getRuntimeMXBean().getName().replace('@', '-');
    }

    /**
     * 轮询并处理任务。
     */
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        List<AsyncTaskEntity> claimed;
        try {
            claimed = claimService.claim(workerId, properties.batchSize());
        } catch (RuntimeException e) {
            log.warn("领取异步任务失败", e);
            return;
        }
        for (AsyncTaskEntity task : claimed) {
            process(task);
        }
    }

    /**
     * 回收租约过期的任务，处理 worker 崩溃留下的永久占用任务。
     */
    public void recover() {
        if (!properties.enabled()) {
            return;
        }
        try {
            int recovered = claimService.recoverExpiredLeases();
            if (recovered > 0) {
                log.warn("回收租约过期的异步任务 count={}", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("回收异步任务租约失败", e);
        }
    }

    private void process(AsyncTaskEntity task) {
        TaskType taskType;
        try {
            taskType = TaskType.fromValue(task.getTaskType());
        } catch (IllegalArgumentException e) {
            log.error("任务类型未知, 直接判定失败 taskId={} taskType={}", task.getId(), task.getTaskType());
            claimService.fail(task.getId(), Masking.mask("Unknown task type: " + task.getTaskType()),
                maxRetryOf(task), maxRetryOf(task));
            return;
        }
        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            log.error("没有注册的任务处理器, taskId={} taskType={}", task.getId(), taskType);
            claimService.fail(task.getId(), Masking.mask("No handler for " + taskType.getValue()),
                maxRetryOf(task), maxRetryOf(task));
            return;
        }

        ActorContextHolder.set(ActorContext.system(UUID.fromString(task.getUserId())));
        long start = System.currentTimeMillis();
        try {
            handler.handle(task);
            claimService.succeed(task.getId());
            log.info("异步任务完成 taskId={} taskType={} costMs={}", task.getId(), taskType,
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            claimService.fail(task.getId(), Masking.mask(describe(e)), retryCount, maxRetryOf(task));
            log.warn("异步任务失败, 将重试 taskId={} taskType={} retryCount={} error={}",
                task.getId(), taskType, retryCount + 1, Masking.mask(describe(e)));
        } finally {
            ActorContextHolder.clear();
        }
    }

    private static int maxRetryOf(AsyncTaskEntity task) {
        Integer maxRetries = task.getMaxRetries();
        return maxRetries == null || maxRetries <= 0 ? 1 : maxRetries;
    }

    private static String describe(Throwable e) {
        String message = e.getClass().getSimpleName()
            + (e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + e.getMessage());
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
