package com.example.pwt.work.domain;

/**
 * 工作记录的记忆提取进度，用于创建工作记录的响应（设计文档 10.3 的 {@code memoryExtraction}）。
 *
 * <p>取值来自 {@code pt_async_task} 的真实状态，而不是“本次请求有没有插入任务”：
 * 幂等命中时任务早已排队，若照报“未排队”就是假信息；已成功的任务也不是“queued”。
 *
 * @author pwt
 */
public enum ExtractionState {

    /** 已排队待执行。 */
    QUEUED("queued"),
    /** 正在执行。 */
    RUNNING("running"),
    /** 已执行成功。 */
    SUCCEEDED("succeeded"),
    /** 重试耗尽后失败，可在后台重放。 */
    FAILED("failed"),
    /** 没有排队提取任务。 */
    NOT_QUEUED("notQueued");

    private final String value;

    ExtractionState(String value) {
        this.value = value;
    }

    /**
     * 获取响应中的字符串取值。
     *
     * @return 取值
     */
    public String getValue() {
        return value;
    }
}
