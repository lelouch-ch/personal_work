package com.example.pwt.work.controller.dto;

import com.example.pwt.work.infrastructure.ProjectEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 项目响应。
 *
 * @param id          项目 ID
 * @param name        项目名称
 * @param description 项目说明
 * @param status      状态
 * @param visibility  可见性
 * @param startedAt   开始日期
 * @param endedAt     结束日期
 * @param metadata    扩展元数据
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @author pwt
 */
public record ProjectResponse(UUID id, String name, String description, String status, String visibility,
                              LocalDate startedAt, LocalDate endedAt, Map<String, Object> metadata,
                              Instant createdAt, Instant updatedAt) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 项目实体
     * @return 响应对象
     */
    public static ProjectResponse from(ProjectEntity entity) {
        Map<String, Object> meta = entity.getMetadata() == null ? Map.of() : entity.getMetadata();
        return new ProjectResponse(UUID.fromString(entity.getId()), entity.getName(),
            entity.getDescription(), entity.getStatus(), entity.getVisibility(), entity.getStartedAt(),
            entity.getEndedAt(), meta, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
