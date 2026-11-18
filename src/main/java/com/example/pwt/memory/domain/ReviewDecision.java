package com.example.pwt.memory.domain;

/**
 * 审核决策，与 {@code pt_memory_review.decision} 的 CHECK 取值一致（设计文档 6.4）。
 *
 * @author pwt
 */
public enum ReviewDecision {

    /** 系统自动批准。 */
    AUTO_APPROVE("AUTO_APPROVE"),
    /** 系统判定需人工审核。 */
    PENDING_REVIEW("PENDING_REVIEW"),
    /** Owner 批准。 */
    APPROVE("APPROVE"),
    /** Owner 拒绝。 */
    REJECT("REJECT"),
    /** 与既有记忆合并。 */
    MERGE("MERGE"),
    /** 替代既有记忆。 */
    SUPERSEDE("SUPERSEDE");

    private final String value;

    ReviewDecision(String value) {
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
     * 按存储值解析决策。
     *
     * @param value 存储值
     * @return 决策枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static ReviewDecision fromValue(String value) {
        for (ReviewDecision decision : values()) {
            if (decision.value.equals(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("Unknown review decision: " + value);
    }
}
