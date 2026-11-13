package com.example.pwt.work.domain;

/**
 * 工作记录状态，与 {@code pt_work_log.status} 的 CHECK 取值一致（设计文档 5.4）。
 *
 * @author pwt
 */
public enum WorkLogStatus {

    /** 正常记录。 */
    ACTIVE("active"),
    /** 已归档，不参与默认检索。 */
    ARCHIVED("archived"),
    /** 逻辑删除，是否保留正文按隐私策略决定。 */
    DELETED("deleted");

    private final String value;

    WorkLogStatus(String value) {
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
    public static WorkLogStatus fromValue(String value) {
        for (WorkLogStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown work log status: " + value);
    }
}
