package com.example.pwt.mcp;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.application.CredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 入口的认证过滤器：把凭证换成主体，交给后续传输层。
 *
 * <p>为什么用 Filter 而不是复用 {@code AuthenticationInterceptor}：那个拦截器挂在 Spring MVC 的
 * {@code HandlerMapping} 上，而 MCP 端点是独立注册的 Servlet，拦截器根本不会执行；
 * 若在此处不做认证，未带凭证的客户端就能与服务器完成协议握手。
 *
 * <p>解析结果放进 request attribute，由 {@link McpContext#extract} 在传输层取走。
 * 之所以不能像 REST 那样直接绑 {@code ThreadLocal}：工具回调由 SDK 在 Reactor 线程上调度，
 * 与进入 Servlet 的线程不是同一个，跨线程只能靠协议自带的 transport context 显式传递。
 *
 * <p>失败时直接写 401 并返回统一错误结构，不进入协议层：认证失败是 HTTP 层问题，
 * 让客户端拿到 401 比拿到一个「协议成功但工具全部报错」的会话更容易排查。
 *
 * @author pwt
 */
public class McpActorFilter extends OncePerRequestFilter {

    /** 已解析主体在 request attribute 中的键。 */
    static final String ATTRIBUTE_ACTOR = "pwt.mcp.actor";

    /** Bearer 认证头前缀，与 REST 保持一致。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(McpActorFilter.class);

    private final CredentialResolver credentialResolver;

    private final ObjectMapper objectMapper;

    /**
     * 构造过滤器。
     *
     * @param credentialResolver 凭证解析器，与 REST 共用同一实现
     * @param objectMapper       用于写出错误响应
     */
    public McpActorFilter(CredentialResolver credentialResolver, ObjectMapper objectMapper) {
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            request.setAttribute(ATTRIBUTE_ACTOR,
                credentialResolver.requireValid(extractCredential(request)));
        } catch (BizException e) {
            reject(response, e);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, BizException e) throws IOException {
        ErrorCode code = e.getErrorCode();
        log.info("MCP 认证失败: {}", e.getMessage());
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", e.getMessage());
        body.put("data", null);
        body.put("requestId", RequestContext.requestId() == null
            ? Uuids.randomUuidv7String() : RequestContext.requestId());
        objectMapper.writeValue(response.getOutputStream(), body);
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
