package com.example.pwt.profile.controller.dto;

import com.example.pwt.profile.infrastructure.UserProfileEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 个人画像响应。
 *
 * @param userId      用户 ID
 * @param headline    一句话定位
 * @param summary     画像摘要
 * @param profileJson 结构化画像
 * @param visibility  可见性
 * @param updatedAt   更新时间
 * @author pwt
 */
public record ProfileResponse(UUID userId, String headline, String summary,
                              Map<String, Object> profileJson, String visibility, Instant updatedAt) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 画像实体
     * @return 响应对象
     */
    public static ProfileResponse from(UserProfileEntity entity) {
        Map<String, Object> json = entity.getProfileJson() == null ? Map.of() : entity.getProfileJson();
        return new ProfileResponse(UUID.fromString(entity.getUserId()), entity.getHeadline(),
            entity.getSummary(), json, entity.getVisibility(), entity.getUpdatedAt());
    }
}
