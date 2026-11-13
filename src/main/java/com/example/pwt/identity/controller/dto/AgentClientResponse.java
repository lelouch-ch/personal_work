package com.example.pwt.identity.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent 客户端信息。
 *
 * <p>不含密钥摘要；明文密钥只在创建响应中出现一次（设计文档 14.1）。
 *
 * @param clientId     客户端 ID
 * @param clientName   客户端名称
 * @param scopes       授予的 scope
 * @param status       状态
 * @param expiresAt    过期时间
 * @param lastSeenAt   最近使用时间
 * @param createdAt    创建时间
 * @author pwt
 */
public record AgentClientResponse(UUID clientId, String clientName, List<String> scopes, String status,
                                  Instant expiresAt, Instant lastSeenAt, Instant createdAt) {
}
