package com.example.pwt.app.web;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.application.AgentClientService;
import com.example.pwt.identity.application.OwnerTokenService;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：解析 Bearer 凭证并绑定主体上下文。
 *
 * <p>同一入口同时支持两类主体：以 {@code pwt_} 开头的按 Agent API Key 处理，
 * 其余按 Owner JWT 处理。user_id 一律来自凭证，不接受客户端传入（设计文档 14.3）。
 *
 * @author pwt
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    /** Bearer 认证头前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final OwnerTokenService ownerTokenService;

    private final AgentClientService agentClientService;

    /**
     * 构造拦截器。
     *
     * @param ownerTokenService  Owner 令牌服务
     * @param agentClientService Agent 凭证服务
     */
    public AuthenticationInterceptor(OwnerTokenService ownerTokenService, AgentClientService agentClientService) {
        this.ownerTokenService = ownerTokenService;
        this.agentClientService = agentClientService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String credential = extractCredential(request);
        if (credential == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "Missing Authorization header");
        }
        ActorContextHolder.set(resolve(credential));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        ActorContextHolder.clear();
    }

    private ActorContext resolve(String credential) {
        Optional<ActorContext> resolved = credential.startsWith("pwt_")
            ? agentClientService.authenticate(credential)
            : ownerTokenService.verify(credential);
        return resolved.orElseThrow(
            () -> new BizException(ErrorCode.UNAUTHENTICATED, "Invalid or expired credential"));
    }

    private String extractCredential(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String value = header.substring(BEARER_PREFIX.length()).trim();
        return value.isEmpty() ? null : value;
    }
}
