package com.example.pwt.identity.application;

import com.example.pwt.identity.IdentityProperties;
import com.example.pwt.identity.domain.ActorContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owner 会话令牌服务：签发与校验 HS256 JWT。
 *
 * <p>Agent 不使用 JWT，走随机 API Key + 摘要查库，以支持即时撤销（设计文档 14.1）。
 *
 * @author pwt
 */
@Service
public class OwnerTokenService {

    private static final Logger log = LoggerFactory.getLogger(OwnerTokenService.class);

    /** 存放 Agent 客户端 ID 的自定义声明。 */
    private static final String CLAIM_AGENT_CLIENT = "ac";

    private final IdentityProperties properties;

    private final SecretKey secretKey;

    /**
     * 构造令牌服务。
     *
     * @param properties 身份配置
     */
    public OwnerTokenService(IdentityProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.ownerJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为用户签发访问令牌。
     *
     * @param userId 用户 ID
     * @return 令牌与过期时间
     */
    public IssuedToken issue(UUID userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.ownerTokenTtl());
        String token = Jwts.builder()
            .issuer(properties.ownerTokenIssuer())
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * 校验令牌并解析主体。
     *
     * @param token 待校验令牌
     * @return 主体上下文；令牌无效或过期时返回 {@link Optional#empty()}
     */
    public Optional<ActorContext> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(properties.ownerTokenIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return Optional.of(ActorContext.owner(UUID.fromString(claims.getSubject())));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Owner token rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 签发的令牌结果。
     *
     * @param token     JWT 文本
     * @param expiresAt 过期时间
     * @author pwt
     */
    public record IssuedToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedToken{expiresAt=" + expiresAt + ", tokenLength=" + token.length() + '}';
        }
    }

    /**
     * 预留：解析令牌中的 Agent 客户端声明，供后续 MCP 透传场景使用。
     *
     * @param claims JWT 声明
     * @return Agent 客户端 ID，可能为 {@code null}
     */
    static UUID agentClientId(Claims claims) {
        String value = claims.get(CLAIM_AGENT_CLIENT, String.class);
        return value == null ? null : UUID.fromString(value);
    }
}
