package com.example.pwt.identity.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent 客户端创建结果。
 *
 * @param clientId      客户端 ID
 * @param clientName    客户端名称
 * @param scopes        授予的 scope
 * @param expiresAt     过期时间
 * @param accessToken   明文密钥，仅此次返回，遗失后只能轮换重发
 * @param tokenPreview  密钥展示前缀
 * @param warning       使用提示
 * @author pwt
 */
public record AgentClientCreatedResponse(UUID clientId, String clientName, List<String> scopes, Instant expiresAt,
                                         String accessToken, String tokenPreview, String warning) {

    @Override
    public String toString() {
        return "AgentClientCreatedResponse{clientId=" + clientId + ", tokenPreview=" + tokenPreview + "**}";
    }
}
