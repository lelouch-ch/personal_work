package com.example.pwt.task.application;

import com.example.pwt.task.TaskProperties;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.task.infrastructure.AsyncTaskMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务领取事务（设计文档 17.2）。
 *
 * <p>单独成 Bean 是为了让事务代理生效，并且把事务边界收窄：领取事务只包含
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 与 {@code UPDATE}，耗时处理必须在事务提交之后进行，
 * 否则长事务持有行锁会阻塞其他 worker。
 *
 * @author pwt
 */
@Service
public class TaskClaimService {

    private final AsyncTaskMapper asyncTaskMapper;

    private final TaskProperties properties;

    /**
     * 构造服务。
     *
     * @param asyncTaskMapper 任务 Mapper
     * @param properties      任务配置，退避时长由此计算
     */
    public TaskClaimService(AsyncTaskMapper asyncTaskMapper, TaskProperties properties) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.properties = properties;
    }

    /**
     * 在同一短事务内领取一批任务。
     *
     * @param workerId 领取者标识
     * @param limit    本次最多领取条数
     * @return 已置为 running 的任务
     */
    @Transactional(propagation = Propagation.REQUIRED, timeout = 5, rollbackFor = Exception.class)
    public List<AsyncTaskEntity> claim(String workerId, int limit) {
        List<AsyncTaskEntity> picked = asyncTaskMapper.selectClaimable(limit);
        if (picked.isEmpty()) {
            return List.of();
        }
        List<String> ids = picked.stream().map(AsyncTaskEntity::getId).toList();
        asyncTaskMapper.markRunning(ids, workerId);
        return picked;
    }

    /**
     * 标记任务成功。
     *
     * @param taskId 任务 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void succeed(String taskId) {
        asyncTaskMapper.markSucceeded(taskId);
    }

    /**
     * 记录失败并安排退避重试或判定失败。
     *
     * <p>退避按 2 的幂增长：第 n 次失败后等待 {@code backoffBase * 2^(n-1)}，
     * 避免外部依赖尚未恢复时被密集重试。
     *
     * @param taskId     任务 ID
     * @param error      脱敏错误信息
     * @param retryCount 当前已重试次数
     * @param maxRetries 重试上限
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(String taskId, String error, int retryCount, int maxRetries) {
        boolean exhausted = retryCount + 1 >= maxRetries;
        long backoffMillis = properties.backoffBase().toMillis() * (1L << Math.min(retryCount, 10));
        asyncTaskMapper.markRetryOrFailed(taskId, error, Instant.now().plusMillis(backoffMillis), exhausted);
    }

    /**
     * 回收租约过期的 running 任务（设计文档 17.2 崩溃恢复）。
     *
     * @return 回收的任务数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverExpiredLeases() {
        return asyncTaskMapper.recoverExpiredLeases(properties.leaseDuration().toSeconds());
    }
}
