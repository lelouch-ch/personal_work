package com.example.pwt.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.identity.IdentityProperties;
import com.example.pwt.identity.domain.ActorContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Owner 令牌签发与校验测试。
 *
 * @author pwt
 */
class OwnerTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-which-is-long-enough-32+";

    private final UUID userId = UUID.randomUUID();

    @Test
    void shouldIssueAndVerifyOwnerToken() {
        OwnerTokenService service = service(properties(SECRET, Duration.ofHours(1)));

        OwnerTokenService.IssuedToken issued = service.issue(userId);
        Optional<ActorContext> resolved = service.verify(issued.token());

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().userId()).isEqualTo(userId);
        assertThat(resolved.orElseThrow().isOwner()).isTrue();
        assertThat(issued.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void shouldRejectTamperedToken() {
        OwnerTokenService service = service(properties(SECRET, Duration.ofHours(1)));
        String token = service.issue(userId).token();

        assertThat(service.verify(token + "x")).isEmpty();
        assertThat(service.verify("not-a-jwt")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("  ")).isEmpty();
    }

    @Test
    void shouldRejectTokenSignedWithAnotherSecret() {
        OwnerTokenService issuer = service(properties(SECRET, Duration.ofHours(1)));
        OwnerTokenService other = service(properties("a-completely-different-secret-key-32-chars!", Duration.ofHours(1)));

        String token = issuer.issue(userId).token();

        assertThat(other.verify(token)).isEmpty();
    }

    @Test
    void shouldRejectExpiredToken() {
        OwnerTokenService service = service(properties(SECRET, Duration.ofSeconds(-1)));

        String token = service.issue(userId).token();

        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void shouldNotLeakTokenInToString() {
        OwnerTokenService service = service(properties(SECRET, Duration.ofHours(1)));

        OwnerTokenService.IssuedToken issued = service.issue(userId);

        assertThat(issued.toString()).doesNotContain(issued.token());
    }

    @Test
    void shouldFailFastWhenSecretMissing() {
        IdentityProperties blank = new IdentityProperties(Duration.ofHours(1), "pwt", " ", null, null);

        assertThatThrownBy(blank::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("owner-jwt-secret");
    }

    @Test
    void shouldFailFastWhenSecretTooShort() {
        IdentityProperties tooShort = new IdentityProperties(Duration.ofHours(1), "pwt", "abc", null, null);

        assertThatThrownBy(tooShort::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 32");
    }

    @Test
    void shouldApplyDefaultsForOptionalSettings() {
        IdentityProperties minimal = new IdentityProperties(null, null, SECRET, null, null);

        assertThat(minimal.ownerTokenTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(minimal.ownerTokenIssuer()).isEqualTo("pwt");
        assertThat(minimal.agentClockSkew()).isEqualTo(Duration.ofSeconds(30));
    }

    private OwnerTokenService service(IdentityProperties properties) {
        return new OwnerTokenService(properties);
    }

    private IdentityProperties properties(String secret, Duration ttl) {
        return new IdentityProperties(ttl, "pwt", secret, null, Duration.ofSeconds(30));
    }
}
