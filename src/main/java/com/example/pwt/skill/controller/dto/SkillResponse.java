package com.example.pwt.skill.controller.dto;

import com.example.pwt.skill.infrastructure.SkillEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill 响应。
 *
 * <p>REST 与 MCP 的 {@code get_skills} 共用本结构，两个入口的字段语义保持一致。
 * {@code contentMd} 是完整正文：列表与检索场景由调用方自行截断，
 * 服务端不替调用方决定要丢多少内容。
 *
 * @param id              Skill ID
 * @param slug            稳定标识
 * @param name            显示名称
 * @param description     用途说明
 * @param contentMd       Markdown 正文
 * @param version         版本号
 * @param status          状态：draft / published / archived
 * @param visibility      可见性
 * @param sourceMemoryIds 来源记忆 ID
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 * @author pwt
 */
public record SkillResponse(UUID id, String slug, String name, String description, String contentMd,
                            Integer version, String status, String visibility,
                            List<String> sourceMemoryIds, Instant createdAt, Instant updatedAt) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity Skill 实体
     * @return 响应
     */
    public static SkillResponse from(SkillEntity entity) {
        return new SkillResponse(UUID.fromString(entity.getId()), entity.getSlug(), entity.getName(),
            entity.getDescription(), entity.getContentMd(), entity.getVersion(), entity.getStatus(),
            entity.getVisibility(),
            entity.getSourceMemoryIds() == null ? List.of() : List.copyOf(entity.getSourceMemoryIds()),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
