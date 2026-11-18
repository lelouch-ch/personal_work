package com.example.pwt.task.domain;

/**
 * 异步任务类型，取值与设计文档 17.1 一致。
 *
 * @author pwt
 */
public enum TaskType {

    /** 从工作记录提取候选记忆。 */
    EXTRACT_MEMORY("EXTRACT_MEMORY"),
    /** 生成周报。 */
    GENERATE_WEEKLY_REPORT("GENERATE_WEEKLY_REPORT"),
    /** 生成学习建议。 */
    GENERATE_LEARNING_SUGGESTION("GENERATE_LEARNING_SUGGESTION"),
    /** 生成趋势信号分析。 */
    GENERATE_TREND_REPORT("GENERATE_TREND_REPORT"),
    /** 重建检索索引。 */
    REBUILD_SEARCH_INDEX("REBUILD_SEARCH_INDEX");

    private final String value;

    TaskType(String value) {
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
     * 按存储值解析任务类型。
     *
     * @param value 存储值
     * @return 类型枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static TaskType fromValue(String value) {
        for (TaskType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown task type: " + value);
    }
}
