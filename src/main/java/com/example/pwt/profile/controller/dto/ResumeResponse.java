package com.example.pwt.profile.controller.dto;

import com.example.pwt.common.api.Visibility;
import com.example.pwt.profile.infrastructure.ResumeEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 简历响应。
 *
 * @param id          简历 ID
 * @param version     版本号
 * @param title       标题
 * @param contentMd   Markdown 正文
 * @param resumeJson  结构化内容
 * @param visibility  可见性
 * @param isCurrent   是否为当前版本
 * @param createdAt   创建时间
 * @param publicUrl   公开访问路径，仅 public 可见时有值
 * @author pwt
 */
public record ResumeResponse(UUID id, int version, String title, String contentMd,
                             Map<String, Object> resumeJson, String visibility, boolean isCurrent,
                             Instant createdAt, String publicUrl) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 简历实体
     * @return 响应对象
     */
    public static ResumeResponse from(ResumeEntity entity) {
        boolean publicVisible = Visibility.PUBLIC == Visibility.fromValueOrDefault(
            entity.getVisibility(), Visibility.PRIVATE);
        String url = publicVisible ? "/public/" + entity.getUserId() + "/resume" : null;
        Map<String, Object> json = entity.getResumeJson() == null ? Map.of() : entity.getResumeJson();
        return new ResumeResponse(UUID.fromString(entity.getId()), entity.getVersion(), entity.getTitle(),
            entity.getContentMd(), json, entity.getVisibility(), Boolean.TRUE.equals(entity.getIsCurrent()),
            entity.getCreatedAt(), url);
    }
}
