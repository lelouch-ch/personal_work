package com.example.pwt.task.domain;

/**
 * 异步任务状态，与 {@code pt_async_task.status} 的 CHECK 取值一致（设计文档 17.2）。
 *
 * @author pwt
 */
public enum TaskStatus {

    /** 待领取。 */
    PENDING("pending"),
    /** 已被某个 worker 领取并持有租约。 */
    RUNNING("running"),
    /** 执行成功。 */
    SUCCEEDED("succeeded"),
    /** 重试耗尽后判定失败。 */
    FAILED("failed"),
    /** 人工取消。 */
    CANCELLED("cancelled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /**
     * 获取数据库存储值。
     *
     * @return 存储值
     */
    public String getValue() {
        return value;
    }

    /**
     * 按存储值解析状态。
     *
     * @param value 存储值
     * @return 状态枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }
}
