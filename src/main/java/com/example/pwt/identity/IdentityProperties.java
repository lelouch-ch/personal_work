package com.example.pwt.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * 身份与凭证配置。
 *
 * @param ownerTokenTtl      Owner JWT 有效期，默认 12 小时
 * @param ownerTokenIssuer   Owner JWT 签发者标识
 * @param ownerJwtSecret     Owner JWT HS256 签名密钥，至少 32 字符，来自环境变量
 * @param agentTokenPepper   Agent 密钥摘要的服务端 pepper，来自环境变量，可为空
 * @param agentClockSkew     Agent 过期时间判定允许的时钟偏移
 * @author pwt
 */
@ConfigurationProperties(prefix = "pwt.identity")
public record IdentityProperties(
    Duration ownerTokenTtl,
    String ownerTokenIssuer,
    String ownerJwtSecret,
    String agentTokenPepper,
    Duration agentClockSkew) {

    /** HS256 密钥最短长度，对应 256 bit。 */
    private static final int MIN_SECRET_LENGTH = 32;

    /** 默认 Owner 令牌有效期。 */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(12);

    /** 默认签发者标识。 */
    private static final String DEFAULT_ISSUER = "pwt";

    /** 默认时钟偏移容忍。 */
    private static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    /**
     * 补全未显式配置的默认值。
     */
    public IdentityProperties {
        ownerTokenTtl = ownerTokenTtl == null ? DEFAULT_TOKEN_TTL : ownerTokenTtl;
        ownerTokenIssuer = (ownerTokenIssuer == null || ownerTokenIssuer.isBlank()) ? DEFAULT_ISSUER : ownerTokenIssuer;
        agentClockSkew = agentClockSkew == null ? DEFAULT_CLOCK_SKEW : agentClockSkew;
    }

    /**
     * 校验配置可用性，缺失项直接失败启动，避免运行期才暴露密钥问题。
     */
    public void validate() {
        Assert.hasText(ownerJwtSecret,
            "pwt.identity.owner-jwt-secret must be configured via environment variable, see design doc 18.2");
        Assert.isTrue(ownerJwtSecret.length() >= MIN_SECRET_LENGTH,
            "pwt.identity.owner-jwt-secret must be at least " + MIN_SECRET_LENGTH + " characters for HS256");
    }
}
