package com.example.pwt.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.application.AgentClientService;
import com.example.pwt.identity.application.OwnerTokenService;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 认证拦截器测试：验证两类凭证走不同鉴权路径，且不信任客户端传入的 user_id。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationInterceptorTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Mock
    private OwnerTokenService ownerTokenService;

    @Mock
    private AgentClientService agentClientService;

    private AuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthenticationInterceptor(ownerTokenService, agentClientService);
    }

    @AfterEach
    void tearDown() {
        ActorContextHolder.clear();
    }

    @Test
    void shouldRouteAgentKeyToAgentAuthenticationOnly() {
        UUID userId = UUID.randomUUID();
        when(agentClientService.authenticate("pwt_abc123"))
            .thenReturn(Optional.of(ActorContext.agent(userId, UUID.randomUUID(), Set.of(Scope.WORK_WRITE))));
        request.addHeader("Authorization", "Bearer pwt_abc123");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        ActorContext bound = ActorContextHolder.get();
        assertThat(bound).isNotNull();
        assertThat(bound.isOwner()).isFalse();
        assertThat(bound.userId()).isEqualTo(userId);
        verify(ownerTokenService, never()).verify(any());
    }

    @Test
    void shouldRouteJwtToOwnerVerificationOnly() {
        UUID userId = UUID.randomUUID();
        when(ownerTokenService.verify("header.payload.sig"))
            .thenReturn(Optional.of(ActorContext.owner(userId)));
        request.addHeader("Authorization", "Bearer header.payload.sig");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        assertThat(ActorContextHolder.require().userId()).isEqualTo(userId);
        verify(agentClientService, never()).authenticate(any());
    }

    @Test
    void shouldRejectMissingAuthorizationHeader() {
        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(ActorContextHolder.get()).isNull();
    }

    @Test
    void shouldRejectNonBearerScheme() {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void shouldRejectEmptyBearerValue() {
        request.addHeader("Authorization", "Bearer    ");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
            .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectRevokedOrUnknownCredential() {
        when(agentClientService.authenticate("pwt_revoked")).thenReturn(Optional.empty());
        request.addHeader("Authorization", "Bearer pwt_revoked");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void shouldClearContextOnCompletion() {
        when(ownerTokenService.verify("t")).thenReturn(Optional.of(ActorContext.owner(UUID.randomUUID())));
        request.addHeader("Authorization", "Bearer t");
        interceptor.preHandle(request, response, new Object());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(ActorContextHolder.get()).isNull();
    }

    @Test
    void shouldSkipPreflightRequest() {
        request.setMethod("OPTIONS");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(ActorContextHolder.get()).isNull();
    }
}
