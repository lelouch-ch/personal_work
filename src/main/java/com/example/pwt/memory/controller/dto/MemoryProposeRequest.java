package com.example.pwt.memory.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * 提交记忆候选的请求，字段约束与设计文档 9.4 的 {@code propose_memory} Schema 一致。
 *
 * @param memoryType      记忆类型
 * @param content         记忆正文
 * @param summary         摘要
 * @param evidence        来源原文摘录
 * @param sourceWorkLogId 来源工作记录
 * @param visibility      可见性，非 Owner 不得设为 public
 * @param confidence      调用方置信度，服务端规则仍会重新判定处置方式
 * @param expiresAt       过期时间
 * @author pwt
 */
public record MemoryProposeRequest(
    @NotNull(message = "记忆类型不能为空")
    String memoryType,

    @NotNull(message = "记忆正文不能为空")
    @Size(min = 1, max = 10000, message = "记忆正文长度需在 1 到 10000 之间")
    String content,

    @Size(max = 1000, message = "摘要长度不能超过 1000")
    String summary,

    @Size(max = 5000, message = "证据摘录长度不能超过 5000")
    String evidence,

    UUID sourceWorkLogId,

    String visibility,

    Double confidence,

    Instant expiresAt) {
}
