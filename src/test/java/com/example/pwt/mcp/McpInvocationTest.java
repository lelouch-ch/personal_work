package com.example.pwt.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * MCP 执行外壳测试（设计文档 9.5、14.3）。
 *
 * <p>这里守的是三条会静默出错的性质，协议联调只能覆盖当下这一次，改代码时靠它们兜底：
 * <ul>
 *   <li>主体必须从会话传进调用线程。取不到主体时多租户拦截器不会追加 {@code user_id} 条件，
 *       绑错或没绑就是跨用户读数，接口上看不出来；</li>
 *   <li>业务失败要变成 {@code ok=false} 加错误码，而不是把异常抛给协议层；</li>
 *   <li>非预期异常只回通用提示，内部信息不外泄。</li>
 * </ul>
 *
 * @author pwt
 */
class McpInvocationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ActorContextHolder.clear();
        RequestContext.clear();
    }

    @Test
    void shouldReturnSuccessEnvelopeWithRequestId() throws Exception {
        UUID userId = UUID.randomUUID();
        McpSyncServerExchange exchange = exchange(userId, "req-fixed-001");

        McpSchema.CallToolResult result =
            McpInvocation.tool(exchange, "demo", () -> Map.of("hello", "世界"));

        assertThat(result.isError()).isFalse();
        JsonNode body = MAPPER.readTree(text(result));
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("data").get("hello").asText()).isEqualTo("世界");
        // 客户端带上 X-Request-Id 时回链同一个标识，便于把 Agent 日志和审计对上
        assertThat(body.get("meta").get("request_id").asText()).isEqualTo("req-fixed-001");
        assertThat(Instant.parse(body.get("meta").get("generated_at").asText())).isNotNull();
        assertThat(body.get("error").isNull()).isTrue();
    }

    @Test
    void shouldBindActorFromSessionAndRestoreAfterCall() {
        UUID userId = UUID.randomUUID();
        McpSyncServerExchange exchange = exchange(userId, null);
        ActorContextHolder.set(ActorContext.owner(UUID.randomUUID()));
        RequestContext.setRequestId("outer-request");

        AtomicReference<ActorContext> seen = new AtomicReference<>();
        McpInvocation.tool(exchange, "demo", () -> {
            seen.set(ActorContextHolder.require());
            return "ok";
        });

        assertThat(seen.get().userId()).isEqualTo(userId);
        assertThat(RequestContext.requestId()).isEqualTo("outer-request");
        assertThat(ActorContextHolder.require().userId())
            .as("调用结束后要恢复线程上原有的主体，不能把 Agent 的主体留给下一个请求")
            .isNotEqualTo(userId);
    }

    @Test
    void shouldMapBizExceptionToErrorEnvelope() throws Exception {
        McpSyncServerExchange exchange = exchange(UUID.randomUUID(), null);

        McpSchema.CallToolResult result = McpInvocation.tool(exchange, "demo",
            () -> {
                throw BizException.scopeRequired("work:write");
            });

        assertThat(result.isError()).isTrue();
        JsonNode body = MAPPER.readTree(text(result));
        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("error").get("code").asText()).isEqualTo("SCOPE_REQUIRED");
        assertThat(body.get("error").get("message").asText()).contains("work:write");
    }

    @Test
    void shouldHideUnexpectedFailureBehindGenericMessage() throws Exception {
        McpSyncServerExchange exchange = exchange(UUID.randomUUID(), null);

        McpSchema.CallToolResult result = McpInvocation.tool(exchange, "demo", () -> {
            throw new IllegalStateException("SELECT * FROM pt_memory 语法错误 near line 42");
        });

        // 业务失败与非预期失败一律置 isError，客户端只需看一个标志位
        assertThat(result.isError()).isTrue();
        JsonNode body = MAPPER.readTree(text(result));
        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("error").get("message").asText()).doesNotContain("pt_memory");
    }

    @Test
    void shouldFailWhenSessionHasNoAuthenticatedActor() throws Exception {
        McpTransportContext context = new DefaultMcpTransportContext();
        McpSyncServerExchange exchange = new McpSyncServerExchange(
            new McpAsyncServerExchange("s", null, capabilities(), clientInfo(), context));

        JsonNode body = MAPPER.readTree(text(McpInvocation.tool(exchange, "demo", () -> "x")));

        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void shouldReadResourceWithEnvelopeAndEchoUri() throws Exception {
        McpSyncServerExchange exchange = exchange(UUID.randomUUID(), null);
        SyncResourceSpecification spec =
            McpResourceCatalog.fixed("pwt://profile/style", "已批准工作风格", "说明", List::of);

        McpSchema.ReadResourceResult result = spec.readHandler()
            .apply(exchange, new McpSchema.ReadResourceRequest("pwt://profile/style"));

        assertThat(result.contents()).hasSize(1);
        McpSchema.TextResourceContents contents =
            (McpSchema.TextResourceContents) result.contents().get(0);
        assertThat(contents.uri()).isEqualTo("pwt://profile/style");
        assertThat(contents.mimeType()).isEqualTo(McpInvocation.MIME_TYPE);
        JsonNode body = MAPPER.readTree(contents.text());
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("data").isArray()).isTrue();
    }

    private static McpSyncServerExchange exchange(UUID userId, String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(McpActorFilter.ATTRIBUTE_ACTOR,
            ActorContext.agent(userId, UUID.randomUUID(), Set.of()));
        if (requestId != null) {
            request.addHeader("X-Request-Id", requestId);
        }
        // 走真实的抽取器：键名是 McpContext 内部的，手搓 Map 会测成“两个常量一致”而不是行为
        McpTransportContext context = new McpContext().extract(request, new DefaultMcpTransportContext());
        return new McpSyncServerExchange(new McpAsyncServerExchange("session-test", null,
            capabilities(), clientInfo(), context));
    }

    private static McpSchema.ClientCapabilities capabilities() {
        return new McpSchema.ClientCapabilities(Map.of(), null, null, null);
    }

    private static McpSchema.Implementation clientInfo() {
        return new McpSchema.Implementation("mcp-invocation-test", "0");
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
