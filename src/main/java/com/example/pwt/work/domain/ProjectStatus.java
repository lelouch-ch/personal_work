package com.example.pwt.work.domain;

/**
 * 项目状态，与 {@code pt_project.status} 的 CHECK 取值一致。
 *
 * @author pwt
 */
public enum ProjectStatus {

    /** 进行中。 */
    ACTIVE("active"),
    /** 暂停。 */
    PAUSED("paused"),
    /** 已完成。 */
    COMPLETED("completed"),
    /** 已归档。 */
    ARCHIVED("archived");

    private final String value;

    ProjectStatus(String value) {
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
     * 按数据库存储值解析状态。
     *
     * @param value 存储值
     * @return 状态枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static ProjectStatus fromValue(String value) {
        for (ProjectStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown project status: " + value);
    }

    /**
     * 按存储值解析状态，空值回退为默认值。
     *
     * @param value      存储值，可为空
     * @param defaultStatus 为空时使用的默认值
     * @return 状态枚举
     */
    public static ProjectStatus fromValueOrDefault(String value, ProjectStatus defaultStatus) {
        return (value == null || value.isBlank()) ? defaultStatus : fromValue(value);
    }
}
