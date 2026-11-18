package com.example.pwt.memory.controller.dto;

import com.example.pwt.memory.application.MemoryDetail;
import com.example.pwt.memory.infrastructure.MemoryEvidenceEntity;
import com.example.pwt.memory.infrastructure.MemoryReviewEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆详情响应：正文之外附带证据与审核留痕（设计文档 16.3）。
 *
 * @param memory   记忆本体
 * @param evidence 证据列表
 * @param reviews  审核历史
 * @author pwt
 */
public record MemoryDetailResponse(MemoryResponse memory, List<Evidence> evidence, List<Review> reviews) {

    /**
     * 由领域详情构造响应。
     *
     * @param detail 领域详情
     * @return 响应
     */
    public static MemoryDetailResponse from(MemoryDetail detail) {
        return new MemoryDetailResponse(MemoryResponse.from(detail.memory()),
            detail.evidence().stream().map(Evidence::from).toList(),
            detail.reviews().stream().map(Review::from).toList());
    }

    /**
     * 证据。
     *
     * @param id              证据 ID
     * @param workLogId       来源工作记录
     * @param evidenceType    证据类型
     * @param excerpt         原文摘录
     * @param sourceMetadata  来源元数据
     * @param createdAt       创建时间
     * @author pwt
     */
    public record Evidence(UUID id, UUID workLogId, String evidenceType, String excerpt,
                           Map<String, Object> sourceMetadata, Instant createdAt) {

        /**
         * 构造证据视图。
         *
         * @param entity 证据实体
         * @return 视图
         */
        public static Evidence from(MemoryEvidenceEntity entity) {
            return new Evidence(UUID.fromString(entity.getId()),
                entity.getWorkLogId() == null ? null : UUID.fromString(entity.getWorkLogId()),
                entity.getEvidenceType(), entity.getExcerpt(),
                entity.getSourceMetadata() == null ? Map.of() : entity.getSourceMetadata(),
                entity.getCreatedAt());
        }
    }

    /**
     * 审核留痕。
     *
     * @param id           记录 ID
     * @param reviewType   审核类型
     * @param riskLevel    风险等级
     * @param decision     决策
     * @param reason       原因
     * @param reviewerType 审核人类型
     * @param createdAt    时间
     * @author pwt
     */
    public record Review(UUID id, String reviewType, String riskLevel, String decision, String reason,
                         String reviewerType, Instant createdAt) {

        /**
         * 构造审核视图。
         *
         * @param entity 审核实体
         * @return 视图
         */
        public static Review from(MemoryReviewEntity entity) {
            return new Review(UUID.fromString(entity.getId()), entity.getReviewType(), entity.getRiskLevel(),
                entity.getDecision(), entity.getReason(), entity.getReviewerType(), entity.getCreatedAt());
        }
    }
}
