package com.example.pwt.task.domain;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * 任务 payload 的字段契约。
 *
 * <p>payload 的字段名由入队方与处理器共享。把它放在 task 领域层而不是某个业务模块，
 * 是为了让“采集工作记录”只依赖 task，不反向依赖 memory：入队方不需要知道谁消费这个任务。
 *
 * @author pwt
 */
public final class TaskPayload {

    /** 工作记录 ID 字段名。 */
    public static final String WORK_LOG_ID = "workLogId";

    private TaskPayload() {
    }

    /**
     * 组装以工作记录为业务对象的 payload。
     *
     * @param workLogId 工作记录 ID
     * @return payload
     */
    public static Map<String, Object> ofWorkLog(UUID workLogId) {
        return Map.of(WORK_LOG_ID, workLogId.toString());
    }

    /**
     * 读取工作记录 ID。
     *
     * @param payload 任务 payload
     * @return 工作记录 ID
     * @throws BizException 字段缺失或格式非法
     */
    public static UUID requireWorkLogId(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get(WORK_LOG_ID);
        if (value == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "任务 payload 缺少 " + WORK_LOG_ID);
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "任务 payload 的 " + WORK_LOG_ID + " 非法：" + value, e);
        }
    }
}
