package com.example.pwt.memory.application;

import com.example.pwt.memory.infrastructure.MemoryEntity;
import java.util.List;

/**
 * 一次记忆召回的结果。
 *
 * @param candidates 候选记忆，已按可见性与有效期过滤
 * @param fullText   是否来自全文检索；false 表示走的是 {@code LIKE} 兜底，
 *                   相关度按固定低分计（设计文档 11.4）
 */
public record MemoryRecall(List<MemoryEntity> candidates, boolean fullText) {

    public MemoryRecall {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * 空召回。
     *
     * @return 结果
     */
    public static MemoryRecall empty() {
        return new MemoryRecall(List.of(), false);
    }
}
