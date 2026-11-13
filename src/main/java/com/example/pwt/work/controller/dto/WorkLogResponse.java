package com.example.pwt.work.controller.dto;

import com.example.pwt.work.infrastructure.WorkLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 工作记录响应。
 *
 * @param id               记录 ID
 * @param projectId        关联项目
 * @param sourceType       来源：manual / agent / import
 * @param title            标题
 * @param summary          摘要
 * @param content          正文
 * @param workType         工作类型
 * @param visibility       可见性
 * @param status           状态
 * @param occurredAt       发生时间
 * @param tags             标签，已按小写归一
 * @param memoryExtraction 记忆提取状态
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @author pwt
 */
public record WorkLogResponse(UUID id, UUID projectId, String sourceType, String title, String summary,
                              String content, String workType, String visibility, String status,
                              Instant occurredAt, List<String> tags, String memoryExtraction,
                              Instant createdAt, Instant updatedAt) {

    /** 记忆提取待排队：M4 的异步任务表接入后由 Worker 置为此值。 */
    public static final String EXTRACTION_QUEUED = "queued";

    /** 记忆提取尚未启用：当前版本未创建提取任务，如实返回而不是假称已排队。 */
    public static final String EXTRACTION_NOT_QUEUED = "notQueued";

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 工作记录实体
     * @return 响应对象
     */
    public static WorkLogResponse from(WorkLogEntity entity) {
        return from(entity, EXTRACTION_NOT_QUEUED);
    }

    /**
     * 由持久化对象构造响应并指定记忆提取状态。
     *
     * @param entity           工作记录实体
     * @param memoryExtraction 记忆提取状态
     * @return 响应对象
     */
    public static WorkLogResponse from(WorkLogEntity entity, String memoryExtraction) {
        List<String> tags = entity.getTags() == null ? List.of() : List.copyOf(entity.getTags());
        return new WorkLogResponse(UUID.fromString(entity.getId()),
            entity.getProjectId() == null ? null : UUID.fromString(entity.getProjectId()),
            entity.getSourceType(), entity.getTitle(), entity.getSummary(), entity.getContent(),
            entity.getWorkType(), entity.getVisibility(), entity.getStatus(), entity.getOccurredAt(),
            tags, memoryExtraction, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
