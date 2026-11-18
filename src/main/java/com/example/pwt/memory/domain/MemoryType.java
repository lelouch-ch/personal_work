package com.example.pwt.memory.domain;

/**
 * 六类长期记忆，取值与 {@code pt_memory.memory_type} 的 CHECK 一致（设计文档 5.1、ADR-008）。
 *
 * @author pwt
 */
public enum MemoryType {

    /** 可验证的客观信息。 */
    FACT("fact"),
    /** 用户稳定的选择偏好。 */
    PREFERENCE("preference"),
    /** 从实践中形成的可复用经验。 */
    EXPERIENCE("experience"),
    /** 已做选择及其原因。 */
    DECISION("decision"),
    /** 必须遵守或禁止事项。 */
    CONSTRAINT("constraint"),
    /** 阶段性或长期目标。 */
    GOAL("goal");

    private final String value;

    MemoryType(String value) {
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
     * 按存储值解析类型。
     *
     * @param value 存储值
     * @return 类型枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static MemoryType fromValue(String value) {
        for (MemoryType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown memory type: " + value);
    }
}
