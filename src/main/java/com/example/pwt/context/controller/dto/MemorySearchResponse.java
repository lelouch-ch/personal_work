package com.example.pwt.context.controller.dto;

import com.example.pwt.context.application.ContextSearchService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 记忆检索响应。
 *
 * @param hits       命中列表
 * @param recallPath 召回路径：{@code full_text} 或 {@code like}
 * @author pwt
 */
public record MemorySearchResponse(List<Hit> hits, @JsonProperty("recall_path") String recallPath) {

    /**
     * 由检索结果构造响应。
     *
     * @param result 检索结果
     * @return 响应
     */
    public static MemorySearchResponse from(ContextSearchService.SearchResult result) {
        return new MemorySearchResponse(result.hits().stream().map(Hit::from).toList(), result.recallPath());
    }

    /**
     * 一条命中。
     *
     * @param id         记忆 ID
     * @param type       类型
     * @param summary    摘要
     * @param confidence 置信度
     * @param score      加权总分
     * @param sourceRefs 来源工作记录
     * @param riskLevel  风险等级
     * @param projectId  归属项目
     * @param createdAt  创建时间
     * @author pwt
     */
    public record Hit(UUID id, String type, String summary, BigDecimal confidence, double score,
                      @JsonProperty("source_refs") List<UUID> sourceRefs,
                      @JsonProperty("risk_level") String riskLevel,
                      @JsonProperty("project_id") UUID projectId,
                      @JsonProperty("created_at") Instant createdAt) {

        /**
         * 构造视图。
         *
         * @param hit 检索命中
         * @return 视图
         */
        public static Hit from(ContextSearchService.Hit hit) {
            return new Hit(hit.id(), hit.type(), hit.text(), hit.confidence(), hit.score(),
                hit.sourceRefs(), hit.riskLevel(), hit.projectId(), hit.createdAt());
        }
    }
}
