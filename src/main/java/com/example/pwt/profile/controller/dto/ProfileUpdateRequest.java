package com.example.pwt.profile.controller.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 更新个人画像请求。
 *
 * @param headline     一句话定位
 * @param summary      画像摘要
 * @param profileJson  结构化画像，为空表示保持原值
 * @param visibility   可见性，为空表示保持原值
 * @author pwt
 */
public record ProfileUpdateRequest(
    @Size(max = 255, message = "一句话定位长度不能超过 255")
    String headline,

    @Size(max = 4000, message = "画像摘要长度不能超过 4000")
    String summary,

    Map<String, Object> profileJson,

    String visibility) {
}
