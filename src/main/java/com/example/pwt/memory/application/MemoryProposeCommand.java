package com.example.pwt.memory.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 提交记忆候选的命令（设计文档 9.4 {@code propose_memory}）。
 *
 * @param memoryType       记忆类型
 * @param content          记忆正文
 * @param summary          摘要，可为空
 * @param evidence         来源原文摘录，可为空
 * @param sourceWorkLogId  来源工作记录，可为空
 * @param visibility       可见性，为空表示 private；非 Owner 不得设为 public
 * @param confidence       调用方给出的置信度，可为空，最终仍受服务端规则约束
 * @param expiresAt        过期时间，可为空
 * @param extractionMetadata 提取留痕：模型、提示词版本等，随证据一并落库
 *                            （设计文档 6.6、12.3；pt_memory 表没有对应列）
 * @author pwt
 */
public record MemoryProposeCommand(String memoryType, String content, String summary, String evidence,
                                   UUID sourceWorkLogId, String visibility, Double confidence,
                                   Instant expiresAt, Map<String, Object> extractionMetadata) {

    /**
     * 构造不带提取留痕的命令，用于 Owner 手动新建与 Agent 直接提交。
     *
     * @param memoryType      记忆类型
     * @param content         正文
     * @param summary         摘要
     * @param evidence        证据摘录
     * @param sourceWorkLogId 来源工作记录
     * @param visibility      可见性
     * @param confidence      置信度
     * @param expiresAt       过期时间
     * @return 命令
     */
    public static MemoryProposeCommand of(String memoryType, String content, String summary, String evidence,
                                          UUID sourceWorkLogId, String visibility, Double confidence,
                                          Instant expiresAt) {
        return new MemoryProposeCommand(memoryType, content, summary, evidence, sourceWorkLogId, visibility,
            confidence, expiresAt, Map.of());
    }
}
