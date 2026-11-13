package com.example.pwt.profile.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 新建简历版本请求。
 *
 * @param title       标题
 * @param contentMd   Markdown 正文
 * @param resumeJson  结构化内容
 * @param visibility  可见性，为空表示 private；仅 Owner 可设为 public
 * @param makeCurrent 是否设为当前版本
 * @author pwt
 */
public record ResumeCreateRequest(
    @NotBlank(message = "简历标题不能为空")
    @Size(max = 200, message = "简历标题长度不能超过 200")
    String title,

    @NotBlank(message = "简历正文不能为空")
    @Size(max = 30000, message = "简历正文长度不能超过 30000")
    String contentMd,

    Map<String, Object> resumeJson,

    String visibility,

    Boolean makeCurrent) {

    /**
     * 是否设为当前版本，缺省为是。
     *
     * @return true 表示设为当前版本
     */
    public boolean currentOrDefault() {
        return makeCurrent == null || makeCurrent;
    }
}
