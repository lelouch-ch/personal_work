package com.example.pwt.context.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 构建上下文的请求，字段约束与设计文档 9.4 的 {@code build_context} Schema 一致。
 *
 * @param task          任务描述
 * @param projectId     项目
 * @param tokenBudget   Token 预算，为空取服务端默认值
 * @param includeTypes  记忆类型过滤，为空表示不限
 * @param includeSkills 是否包含 Skill，为空按包含处理
 * @author pwt
 */
public record ContextBuildRequest(
    @NotBlank(message = "任务描述不能为空")
    @Size(max = 5000, message = "任务描述长度不能超过 5000")
    String task,

    UUID projectId,

    Integer tokenBudget,

    @Size(max = 6, message = "include_types 最多 6 项")
    List<String> includeTypes,

    Boolean includeSkills) {
}
