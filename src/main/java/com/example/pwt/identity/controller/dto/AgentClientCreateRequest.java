package com.example.pwt.identity.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/**
 * 创建 Agent 客户端请求。
 *
 * @param clientName 客户端名称
 * @param clientType 客户端类型，可为空，默认 agent
 * @param scopes     授予的 scope，取值必须是设计文档 14.2 定义的集合
 * @param expiresAt  过期时间，可为空表示长期有效
 * @author pwt
 */
public record AgentClientCreateRequest(
    @NotBlank(message = "客户端名称不能为空")
    @Size(max = 150, message = "客户端名称长度不能超过 150")
    String clientName,

    @Size(max = 40, message = "客户端类型长度不能超过 40")
    String clientType,

    @NotEmpty(message = "至少授予一个 scope")
    @Size(max = 8, message = "scope 数量不能超过 8")
    Set<String> scopes,

    Instant expiresAt) {
}
