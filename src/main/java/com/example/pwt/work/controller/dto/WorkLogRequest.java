package com.example.pwt.work.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 创建工作记录的请求，字段约束与设计文档 9.4 的 {@code capture_work_log} Schema 一致。
 *
 * @param projectId  关联项目，可为空
 * @param title      标题
 * @param summary    摘要
 * @param content    正文
 * @param workType   工作类型，为空表示 general
 * @param occurredAt 发生时间
 * @param tags       标签，最多 20 个
 * @param visibility 可见性，为空表示 private；非 Owner 不得设为 public
 * @author pwt
 */
public record WorkLogRequest(
    UUID projectId,

    @NotNull(message = "标题不能为空")
    @Size(min = 1, max = 300, message = "标题长度需在 1 到 300 之间")
    String title,

    @Size(max = 5000, message = "摘要长度不能超过 5000")
    String summary,

    @Size(max = 30000, message = "正文长度不能超过 30000")
    String content,

    String workType,

    @NotNull(message = "发生时间不能为空")
    Instant occurredAt,

    @Size(max = 20, message = "标签数量不能超过 20")
    List<@Size(max = 50, message = "单个标签长度不能超过 50") String> tags,

    String visibility) {
}
