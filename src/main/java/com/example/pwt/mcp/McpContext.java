package com.example.pwt.mcp;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 把 HTTP 请求上的主体带进 MCP 会话，并在工具执行时取回。
 *
 * <p>传输层提供了 {@link McpTransportContextExtractor} 这个官方扩展点，正是为此设计：
 * 它在请求线程上执行（此时 {@code HttpServletRequest} 可用），把要跨线程传递的东西放进
 * context，之后在 {@code McpSyncServerExchange#transportContext()} 上取回。
 *
 * <p>不能改用 ThreadLocal 直接传递：SDK 把工具回调调度到 Reactor 线程上执行，
 * 与进入 Servlet 的线程不同；而本项目多一条硬约束——多租户拦截器在取不到主体时
 * <b>不追加</b> {@code user_id} 条件（见 {@code MybatisPlusConfig#ignoreTable}），
 * 一旦工具线程上没有主体就查询，会静默读到全部用户的数据。
 * 因此 {@link #actor} 取不到主体时直接抛未认证，绝不放行。
 *
 * @author pwt
 */
public final class McpContext implements McpTransportContextExtractor<HttpServletRequest> {

    /** 主体在 transport context 中的键。 */
    private static final String KEY_ACTOR = "pwt.actor";

    /** 请求标识在 transport context 中的键，供审计与响应 meta 使用。 */
    private static final String KEY_REQUEST_ID = "pwt.requestId";

    @Override
    public McpTransportContext extract(HttpServletRequest request, McpTransportContext context) {
        Object actor = request.getAttribute(McpActorFilter.ATTRIBUTE_ACTOR);
        if (actor instanceof ActorContext parsed) {
            context.put(KEY_ACTOR, parsed);
        }
        String requestId = request.getHeader("X-Request-Id");
        if (requestId != null && !requestId.isBlank()) {
            context.put(KEY_REQUEST_ID, requestId.trim());
        }
        return context;
    }

    /**
     * 取本次调用的主体。
     *
     * @param exchange 会话
     * @return 主体上下文
     * @throws BizException 上下文缺失，或其中没有主体（说明请求没经过认证过滤器）
     */
    public static ActorContext actor(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange == null ? null : exchange.transportContext();
        if (context == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED,
                "MCP 会话缺少 transport context，无法确定数据所有者");
        }
        Object value = context.get(KEY_ACTOR);
        if (!(value instanceof ActorContext actor)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "MCP 会话未绑定已认证主体");
        }
        return actor;
    }

    /**
     * 取请求进入时客户端带来的 {@code X-Request-Id}，用于在工具结果里回链。
     *
     * @param exchange 会话
     * @return 请求标识，客户端未传时为 {@code null}
     */
    public static String requestId(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange == null ? null : exchange.transportContext();
        Object value = context == null ? null : context.get(KEY_REQUEST_ID);
        return value == null ? null : String.valueOf(value);
    }
}
