package com.example.pwt.memory.domain;

/**
 * 记忆风险等级，与 {@code pt_memory.risk_level}、{@code pt_memory_review.risk_level} 的 CHECK 一致
 * （设计文档 6.2）。
 *
 * <p>等级按处理严格程度递增，可用 {@link #atMost(RiskLevel)} 表达“不高于某档”的自动批准条件。
 *
 * @author pwt
 */
public enum RiskLevel {

    /** 明确、低风险事实。 */
    L0,
    /** 偏好、经验或轻度推断。 */
    L1,
    /** 敏感、影响较大的信息。 */
    L2,
    /** 凭证和高危秘密。 */
    L3;

    /**
     * 判断本等级是否不高于给定等级。
     *
     * @param ceiling 上限等级
     * @return true 表示不高于上限
     */
    public boolean atMost(RiskLevel ceiling) {
        return this.ordinal() <= ceiling.ordinal();
    }

    /**
     * 取两个等级中更严格的一个。
     *
     * @param other 另一等级，可为空
     * @return 更严格的等级
     */
    public RiskLevel max(RiskLevel other) {
        return other == null || this.ordinal() >= other.ordinal() ? this : other;
    }

    /**
     * 按存储值解析等级。
     *
     * @param value 存储值
     * @return 等级枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static RiskLevel fromValue(String value) {
        for (RiskLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown risk level: " + value);
    }
}
