package com.example.pwt.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 主体上下文权限判定测试（设计文档 14.2、14.3）。
 *
 * @author pwt
 */
class ActorContextTest {

    private final UUID userId = UUID.randomUUID();

    private final UUID otherUserId = UUID.randomUUID();

    private final UUID clientId = UUID.randomUUID();

    @Test
    void ownerShouldBypassScopeChecks() {
        ActorContext owner = ActorContext.owner(userId);

        assertThat(owner.isOwner()).isTrue();
        assertThat(owner.hasScope(Scope.WORK_WRITE)).isTrue();
        assertThat(owner.scopes()).isEmpty();
        assertThatCode(() -> owner.requireScope(Scope.REPORT_GENERATE)).doesNotThrowAnyException();
    }

    @Test
    void agentShouldBeRejectedWhenScopeMissing() {
        ActorContext agent = ActorContext.agent(userId, clientId, Set.of(Scope.WORK_WRITE));

        assertThat(agent.isOwner()).isFalse();
        assertThat(agent.hasScope(Scope.WORK_WRITE)).isTrue();
        assertThat(agent.hasScope(Scope.MEMORY_READ)).isFalse();
        assertThatThrownBy(() -> agent.requireScope(Scope.MEMORY_READ))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_REQUIRED);
    }

    @Test
    void agentShouldNotHoldAnyScopeByDefault() {
        ActorContext agent = ActorContext.agent(userId, clientId, null);

        assertThat(agent.scopes()).isEmpty();
        assertThat(agent.hasScope(Scope.PROFILE_READ)).isFalse();
    }

    @Test
    void scopesShouldBeImmutableCopy() {
        Set<String> mutable = new java.util.HashSet<>();
        mutable.add(Scope.WORK_READ);

        ActorContext agent = ActorContext.agent(userId, clientId, mutable);
        mutable.add(Scope.MEMORY_READ);

        assertThat(agent.scopes()).containsExactly(Scope.WORK_READ);
    }

    @Test
    void crossUserAccessShouldSurfaceAsNotFound() {
        ActorContext owner = ActorContext.owner(userId);

        assertThatCode(() -> owner.requireOwnership(userId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> owner.requireOwnership(otherUserId))
            .isInstanceOf(BizException.class)
            .hasMessage("resource not found")
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
