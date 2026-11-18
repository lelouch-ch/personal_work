package com.example.pwt.work.controller.dto;

import com.example.pwt.work.domain.ExtractionState;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param memoryExtraction 记忆提取进度，取自 {@code pt_async_task}；列表不逐行解析该状态，此时字段省略
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @author pwt
 */
public record WorkLogResponse(UUID id, UUID projectId, String sourceType, String title, String summary,
                              String content, String workType, String visibility, String status,
                              Instant occurredAt, List<String> tags,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String memoryExtraction,
                              Instant createdAt, Instant updatedAt) {

    /**
     * 由持久化对象构造响应，不附带提取进度。
     *
     * <p>用于列表：逐行回查 {@code pt_async_task} 会对一页数据产生同等数量的额外查询，
     * 而列表并不需要这个字段。宁可不返回，也不返回一个没有解析过的固定值。
     *
     * @param entity 工作记录实体
     * @return 响应对象
     */
    public static WorkLogResponse from(WorkLogEntity entity) {
        return build(entity, null);
    }

    /**
     * 由持久化对象构造响应并附带真实的提取进度。
     *
     * @param entity     工作记录实体
     * @param extraction 提取进度，来自任务表；为 {@code null} 时省略该字段
     * @return 响应对象
     */
    public static WorkLogResponse from(WorkLogEntity entity, ExtractionState extraction) {
        return build(entity, extraction == null ? null : extraction.getValue());
    }

    private static WorkLogResponse build(WorkLogEntity entity, String memoryExtraction) {
        List<String> tags = entity.getTags() == null ? List.of() : List.copyOf(entity.getTags());
        return new WorkLogResponse(UUID.fromString(entity.getId()),
            entity.getProjectId() == null ? null : UUID.fromString(entity.getProjectId()),
            entity.getSourceType(), entity.getTitle(), entity.getSummary(), entity.getContent(),
            entity.getWorkType(), entity.getVisibility(), entity.getStatus(), entity.getOccurredAt(),
            tags, memoryExtraction, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
