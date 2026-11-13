package com.example.pwt.common.api;

/**
 * 可见性级别，与业务表 {@code visibility} 列的 CHECK 取值一致（设计文档 5.3）。
 *
 * @author pwt
 */
public enum Visibility {

    /** 仅 Owner 可见。 */
    PRIVATE("private"),
    /** 供持有相应 scope 的 Agent 使用，但不公开展示。 */
    AGENT("agent"),
    /** 允许匿名访问，例如公开简历与个人主页。 */
    PUBLIC("public");

    private final String value;

    Visibility(String value) {
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
     * 按数据库存储值解析可见性。
     *
     * @param value 存储值
     * @return 可见性枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static Visibility fromValue(String value) {
        for (Visibility visibility : values()) {
            if (visibility.value.equals(value)) {
                return visibility;
            }
        }
        throw new IllegalArgumentException("Unknown visibility: " + value);
    }

    /**
     * 按存储值解析可见性，空值回退为默认级别。
     *
     * @param value         存储值，可为空
     * @param defaultVisibility 为空时使用的默认值
     * @return 可见性枚举
     */
    public static Visibility fromValueOrDefault(String value, Visibility defaultVisibility) {
        return (value == null || value.isBlank()) ? defaultVisibility : fromValue(value);
    }
}
