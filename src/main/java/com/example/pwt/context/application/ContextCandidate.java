package com.example.pwt.context.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 参与排序与预算分配的候选项。
 *
 * <p>由 {@link ContextDataReader} 从实体映射而来：排序、去重与预算分配全部只依赖本记录，
 * 不碰数据库也不碰 MyBatis 实体，因此可以给定输入直接断言输出。
 *
 * @param id          记忆 ID
 * @param type        记忆类型存储值
 * @param text        实际注入的文本，已按摘要优先、正文截断选定
 * @param importance  重要度列值
 * @param confidence  置信度列值
 * @param createdAt   创建时间
 * @param lastUsedAt  最近使用时间，可为空
 * @param projectId   归属项目，可为空表示项目未知
 * @param relevance   检索相关度原始分数；{@code LIKE} 兜底命中时为空
 * @param riskLevel   风险等级
 * @param sourceRefs  来源工作记录 ID
 */
public record ContextCandidate(UUID id, String type, String text, Integer importance, BigDecimal confidence,
                               Instant createdAt, Instant lastUsedAt, UUID projectId, Double relevance,
                               String riskLevel, List<UUID> sourceRefs) {

    /** 来源引用最多给出多少条：一条记忆的证据可能几十条，全列出来挤占预算却没有额外信息量。 */
    private static final int MAX_SOURCE_REFS = 5;

    public ContextCandidate {
        sourceRefs = sourceRefs == null || sourceRefs.isEmpty()
            ? List.of()
            : List.copyOf(sourceRefs.subList(0, Math.min(MAX_SOURCE_REFS, sourceRefs.size())));
    }
}
