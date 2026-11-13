package com.example.pwt.common.api;

/**
 * 数据来源类型，与业务表 {@code source_type} 的 CHECK 取值一致。
 *
 * <p>无论来源为何，都必须保留来源元数据与审计（设计文档第 2 章）。
 *
 * @author pwt
 */
public enum SourceType {

    /** Owner 在网站手动录入。 */
    MANUAL("manual"),
    /** Agent 通过 MCP 或 REST 提交。 */
    AGENT("agent"),
    /** 批量导入。 */
    IMPORT("import"),
    /** 系统内部生成。 */
    SYSTEM("system");

    private final String value;

    SourceType(String value) {
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
     * 按存储值解析来源类型。
     *
     * @param value 存储值
     * @return 来源枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static SourceType fromValue(String value) {
        for (SourceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source type: " + value);
    }
}
