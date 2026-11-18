package com.example.pwt.task.application;

import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;

/**
 * 任务处理器扩展点。
 *
 * <p>Worker 只负责领取、租约、重试与上下文绑定，具体业务由各领域实现本接口提供，
 * 避免 task 模块反向依赖 memory / report 等业务模块。
 *
 * @author pwt
 */
public interface TaskHandler {

    /**
     * 声明本处理器负责的任务类型。
     *
     * @return 任务类型
     */
    TaskType taskType();

    /**
     * 处理任务。抛出异常视为本次执行失败，由 Worker 记录并重试。
     *
     * @param task 待处理任务，payload 已反序列化
     * @throws Exception 处理失败，异常信息会被脱敏后写入 last_error
     */
    void handle(AsyncTaskEntity task) throws Exception;
}
