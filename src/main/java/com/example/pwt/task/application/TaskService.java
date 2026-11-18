package com.example.pwt.task.application;

import com.example.pwt.common.util.Uuids;
import com.example.pwt.task.TaskProperties;
import com.example.pwt.task.domain.TaskStatus;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.task.infrastructure.AsyncTaskMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异步任务入队服务（设计文档 17.1、17.3）。
 *
 * <p>入队与业务写入同事务：业务回滚时任务不应留下，否则 Worker 会读到不存在的业务对象。
 * 去重由 {@code dedup_key} 唯一索引兜底，并发重复入队返回既有任务。
 *
 * @author pwt
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final AsyncTaskMapper asyncTaskMapper;

    private final TaskProperties properties;

    /**
     * 构造服务。
     *
     * @param asyncTaskMapper 任务 Mapper
     * @param properties      任务配置
     */
    public TaskService(AsyncTaskMapper asyncTaskMapper, TaskProperties properties) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.properties = properties;
    }

    /**
     * 入队一个任务。
     *
     * @param userId   任务所属用户
     * @param taskType 任务类型
     * @param dedupRef 去重引用（通常是业务对象 ID），为空表示不去重
     * @param payload  任务入参
     * @return 新建或既有的任务
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public AsyncTaskEntity enqueue(UUID userId, TaskType taskType, String dedupRef, Map<String, Object> payload) {
        return enqueue(userId, taskType, dedupRef, payload, properties.maxRetryAttempts());
    }

    /**
     * 入队一个任务并指定重试上限。
     *
     * @param userId     任务所属用户
     * @param taskType   任务类型
     * @param dedupRef   去重引用，为空表示不去重
     * @param payload    任务入参
     * @param maxRetries 最大重试次数
     * @return 新建或既有的任务；调用方可据此区分“本次排队”与“早已排队”
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public AsyncTaskEntity enqueue(UUID userId, TaskType taskType, String dedupRef, Map<String, Object> payload,
                                   int maxRetries) {
        String ref = (dedupRef == null || dedupRef.isBlank()) ? null : dedupRef.trim();
        if (ref != null) {
            Optional<AsyncTaskEntity> existing = findExisting(userId, taskType, ref);
            if (existing.isPresent()) {
                log.info("任务已存在, 跳过重复入队 userId={} taskType={} dedupRef={}",
                    userId, taskType.getValue(), ref);
                return existing.get();
            }
        }

        Instant now = Instant.now();
        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(userId.toString());
        entity.setTaskType(taskType.getValue());
        entity.setDedupRef(ref);
        entity.setPayload(payload == null ? Map.of() : Map.copyOf(payload));
        entity.setStatus(TaskStatus.PENDING.getValue());
        entity.setRetryCount(0);
        entity.setMaxRetries(maxRetries);
        entity.setNextRetryAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            asyncTaskMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            // 并发入队：唯一索引 uq_async_task_dedup 拦截后回读既有任务，保持幂等
            return findExisting(userId, taskType, ref).orElseThrow(() -> e);
        }
    }

    private Optional<AsyncTaskEntity> findExisting(UUID userId, TaskType taskType, String dedupRef) {
        return Optional.ofNullable(
            asyncTaskMapper.selectByDedupKey(userId.toString(), taskType.getValue(), dedupRef));
    }

    /**
     * 查询已排队的任务，用于向调用方回报真实进度而不是照报“未排队”。
     *
     * @param userId   所属用户
     * @param taskType 任务类型
     * @param dedupRef 去重引用
     * @return 任务，不存在时为 {@code null}
     */
    @Transactional(readOnly = true)
    public AsyncTaskEntity find(UUID userId, TaskType taskType, String dedupRef) {
        if (dedupRef == null || dedupRef.isBlank()) {
            return null;
        }
        return asyncTaskMapper.selectByDedupKey(userId.toString(), taskType.getValue(), dedupRef.trim());
    }
}
