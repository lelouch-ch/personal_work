package com.example.pwt.llm;

/**
 * LLM 任务类型（设计文档 12.2）。
 *
 * <p>用于按任务选择模型与模板，并在提示词治理中定位一次生成所用的配置（12.3）。
 *
 * @author pwt
 */
public enum LlmTaskType {

    /** 从工作记录提取候选记忆。 */
    MEMORY_EXTRACTION,
    /** 候选记忆分类。 */
    MEMORY_CLASSIFICATION,
    /** 辅助去重判断。 */
    MEMORY_DEDUP_ASSIST,
    /** 工作记录摘要。 */
    WORK_LOG_SUMMARY,
    /** 周报。 */
    WEEKLY_REPORT,
    /** 学习建议。 */
    LEARNING_SUGGESTION,
    /** 趋势信号分析。 */
    TREND_SIGNAL_ANALYSIS
}
