package com.example.pwt.app.web;

import com.example.pwt.identity.application.CredentialResolver;
import com.example.pwt.identity.domain.ActorContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：解析 Bearer 凭证并绑定主体上下文。
 *
 * <p>凭证到主体的判定交给 {@link CredentialResolver}，与 MCP 入口共用同一实现，
 * 避免两处规则漂移。user_id 一律来自凭证，不接受客户端传入（设计文档 14.3）。
 *
 * @author pwt
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    /** Bearer 认证头前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final CredentialResolver credentialResolver;

    /**
     * 构造拦截器。
     *
     * @param credentialResolver 凭证解析器
     */
    public AuthenticationInterceptor(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        ActorContextHolder.set(credentialResolver.requireValid(extractCredential(request)));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        ActorContextHolder.clear();
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
