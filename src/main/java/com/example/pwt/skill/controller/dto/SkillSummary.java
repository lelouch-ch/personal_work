package com.example.pwt.skill.controller.dto;

import com.example.pwt.skill.infrastructure.SkillEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * Skill 摘要：列表与资源视图用的精简形状，<b>不含正文</b>。
 *
 * <p>单列一个类型而不是复用 {@link SkillResponse}：{@code content_md} 是
 * {@code mediumtext}，一条流程正文就能有上万字，「先看看有哪些 Skill」这种列举场景
 * 把全部正文灌进响应里，Agent 光传输就要花掉预算，还得自己解析出标题。
 * 要正文请按 ID 调 {@code get_skills}。
 *
 * @param id          Skill ID
 * @param slug        稳定标识
 * @param name        显示名称
 * @param description 用途说明
 * @param version     版本号
 * @param visibility  可见性
 * @param updatedAt   更新时间
 * @author pwt
 */
public record SkillSummary(UUID id, String slug, String name, String description, Integer version,
                           String visibility, Instant updatedAt) {

    /**
     * 由持久化对象构造摘要。
     *
     * @param entity Skill 实体
     * @return 摘要
     */
    public static SkillSummary from(SkillEntity entity) {
        return new SkillSummary(UUID.fromString(entity.getId()), entity.getSlug(), entity.getName(),
            entity.getDescription(), entity.getVersion(), entity.getVisibility(),
            entity.getUpdatedAt());
    }
}
