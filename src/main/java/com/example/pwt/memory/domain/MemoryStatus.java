package com.example.pwt.memory.domain;

/**
 * 记忆状态，与 {@code pt_memory.status} 的 CHECK 取值一致（设计文档 5.4、6.4）。
 *
 * <p>状态机只保留可持久化的稳定状态，CAPTURED/EXTRACTED/CLASSIFIED 等过程节点
 * 记录在 {@code pt_memory_review} 与任务表中。
 *
 * @author pwt
 */
public enum MemoryStatus {

    /** 提取后尚未批准。 */
    CANDIDATE("candidate"),
    /** 通过审核，可被 Context Builder 使用。 */
    APPROVED("approved"),
    /** 被拒绝，不可供 Agent 使用。 */
    REJECTED("rejected"),
    /** 历史归档，不再默认参与上下文。 */
    ARCHIVED("archived"),
    /** 被新版本或新记忆替代。 */
    SUPERSEDED("superseded");

    private final String value;

    MemoryStatus(String value) {
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
     * 判断能否批准该状态的记录。
     *
     * <p>{@code approved} 重复批准是空操作，{@code superseded} 则由版本管理负责，
     * 不允许直接改回批准态，否则会出现两条同时生效的相互冲突记忆。
     *
     * @return true 表示可以批准
     */
    public boolean canApprove() {
        return this == CANDIDATE || this == REJECTED;
    }

    /**
     * 判断能否拒绝该状态的记录。
     *
     * @return true 表示可以拒绝
     */
    public boolean canReject() {
        return this == CANDIDATE || this == APPROVED;
    }

    /**
     * 判断能否归档该状态的记录。
     *
     * @return true 表示可以归档
     */
    public boolean canArchive() {
        return this != SUPERSEDED;
    }

    /**
     * 按存储值解析状态。
     *
     * @param value 存储值
     * @return 状态枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static MemoryStatus fromValue(String value) {
        for (MemoryStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown memory status: " + value);
    }
}
