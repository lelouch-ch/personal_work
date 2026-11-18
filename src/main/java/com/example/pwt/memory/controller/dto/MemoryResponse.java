package com.example.pwt.memory.controller.dto;

import com.example.pwt.memory.infrastructure.MemoryEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 记忆响应。
 *
 * @param id            记忆 ID
 * @param memoryType    记忆类型
 * @param content       正文
 * @param summary       摘要
 * @param confidence    置信度
 * @param importance    重要度
 * @param riskLevel     风险等级
 * @param visibility    可见性
 * @param status        状态
 * @param sourceType    来源
 * @param sourceId      来源对象 ID
 * @param version       版本号
 * @param supersedesId  被替代的旧记忆 ID
 * @param validFrom     生效起始
 * @param validUntil    生效结束
 * @param expiresAt     过期时间
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @author pwt
 */
public record MemoryResponse(UUID id, String memoryType, String content, String summary, BigDecimal confidence,
                             Integer importance, String riskLevel, String visibility, String status,
                             String sourceType, UUID sourceId, Integer version, UUID supersedesId,
                             Instant validFrom, Instant validUntil, Instant expiresAt,
                             Instant createdAt, Instant updatedAt) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 记忆实体
     * @return 响应
     */
    public static MemoryResponse from(MemoryEntity entity) {
        return new MemoryResponse(UUID.fromString(entity.getId()), entity.getMemoryType(),
            entity.getContent(), entity.getSummary(), entity.getConfidence(), entity.getImportance(),
            entity.getRiskLevel(), entity.getVisibility(), entity.getStatus(), entity.getSourceType(),
            toUuid(entity.getSourceId()), entity.getVersion(), toUuid(entity.getSupersedesId()),
            entity.getValidFrom(), entity.getValidUntil(), entity.getExpiresAt(), entity.getCreatedAt(),
            entity.getUpdatedAt());
    }

    private static UUID toUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
