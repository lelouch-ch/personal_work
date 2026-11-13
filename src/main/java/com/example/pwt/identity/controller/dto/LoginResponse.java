package com.example.pwt.identity.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 登录结果。
 *
 * @param userId       用户 ID
 * @param accessToken  Bearer 令牌
 * @param tokenType    令牌类型，固定 Bearer
 * @param expiresAt    过期时间，UTC
 * @author pwt
 */
public record LoginResponse(UUID userId, String accessToken, String tokenType, Instant expiresAt) {

    @Override
    public String toString() {
        return "LoginResponse{userId=" + userId + ", tokenType=" + tokenType + ", expiresAt=" + expiresAt + '}';
    }
}
