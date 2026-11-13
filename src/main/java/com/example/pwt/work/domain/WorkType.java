package com.example.pwt.work.domain;

/**
 * 工作记录类型，取值与设计文档 9.4 的 {@code capture_work_log.work_type} 一致。
 *
 * @author pwt
 */
public enum WorkType {

    /** 开发。 */
    DEVELOPMENT("development"),
    /** 调研。 */
    RESEARCH("research"),
    /** 会议。 */
    MEETING("meeting"),
    /** 运维与线上事务。 */
    OPERATION("operation"),
    /** 学习。 */
    LEARNING("learning"),
    /** 未分类。 */
    GENERAL("general");

    private final String value;

    WorkType(String value) {
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
    public static WorkType fromValue(String value) {
        for (WorkType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown work type: " + value);
    }

    /**
     * 按存储值解析类型，空值回退为默认值。
     *
     * @param value         存储值，可为空
     * @param defaultType   为空时使用的默认值
     * @return 类型枚举
     */
    public static WorkType fromValueOrDefault(String value, WorkType defaultType) {
        return (value == null || value.isBlank()) ? defaultType : fromValue(value);
    }
}
