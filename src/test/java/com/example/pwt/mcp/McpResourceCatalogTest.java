package com.example.pwt.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.api.Scope;
import com.example.pwt.context.application.GovernanceQueryService;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.memory.domain.MemoryStatus;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.work.application.WorkLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 资源目录测试（设计文档 9.2）。
 *
 * <p>重点盯模板：SDK 的 {@code resources/read} 只匹配<b>已注册资源</b>的 URI，
 * 模板清单本身不参与匹配。曾经只登记清单没有登记可读资源，
 * 结果 {@code pwt://memory/approved/experience} 一律报“资源不存在”，
 * 而 {@code resources/list} 看起来完全正常，因此这里同时钉住清单与可读性。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class McpResourceCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ProfileService profileService;

    @Mock
    private ResumeService resumeService;

    @Mock
    private GovernanceQueryService governanceQueryService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private WorkLogService workLogService;

    private final UUID userId = UUID.randomUUID();

    private McpResourceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new McpResourceCatalog(profileService, resumeService, governanceQueryService,
            memoryService, workLogService);
    }

    @AfterEach
    void tearDown() {
        ActorContextHolder.clear();
    }

    @Test
    void shouldRegisterSixFixedResourcesPlusSixTemplatedOnes() {
        Map<String, SyncResourceSpecification> resources = catalog.resources();

        assertThat(resources).containsOnlyKeys("pwt://profile/identity", "pwt://profile/resume",
            "pwt://profile/style", "pwt://profile/constraints", "pwt://memory/approved",
            "pwt://work/recent",
            "pwt://memory/approved/fact", "pwt://memory/approved/preference",
            "pwt://memory/approved/experience", "pwt://memory/approved/decision",
            "pwt://memory/approved/constraint", "pwt://memory/approved/goal");
        assertThat(resources).allSatisfy((uri, spec) -> {
            assertThat(spec.resource().uri()).isEqualTo(uri);
            assertThat(spec.resource().name()).isNotBlank();
            assertThat(spec.resource().description()).isNotBlank();
            assertThat(spec.resource().mimeType()).isEqualTo(McpInvocation.MIME_TYPE);
        });
    }

    @Test
    void shouldExposeOnlyTheDocumentedTemplate() {
        assertThat(catalog.resourceTemplates())
            .extracting(McpSchema.ResourceTemplate::uriTemplate)
            .containsExactly(McpResourceCatalog.MEMORY_BY_TYPE_TEMPLATE);
    }

    @Test
    void shouldReadTemplateInstanceForEveryMemoryType() throws Exception {
        when(memoryService.list(any(), any(), eq(MemoryStatus.APPROVED.getValue()), any(PageQuery.class)))
            .thenReturn(PageResult.of(List.of(), 1, 20, 0L));

        for (MemoryType type : MemoryType.values()) {
            String uri = "pwt://memory/approved/" + type.getValue();
            JsonNode body = read(uri);

            assertThat(body.get("ok").asBoolean()).as(uri).isTrue();
            assertThat(body.get("data").get("items").isArray()).as(uri).isTrue();
        }
    }

    @Test
    void shouldFilterByTheTypeSegmentOfUri() throws Exception {
        when(memoryService.list(any(), any(), any(), any(PageQuery.class)))
            .thenReturn(PageResult.of(List.of(), 1, 20, 0L));

        read("pwt://memory/approved/decision");

        verify(memoryService).list(any(ActorContext.class), eq("decision"),
            eq(MemoryStatus.APPROVED.getValue()), any(PageQuery.class));
        verify(memoryService, never()).list(any(ActorContext.class), isNull(),
            eq(MemoryStatus.APPROVED.getValue()), any(PageQuery.class));
    }

    @Test
    void shouldRejectResourceReadWhenScopeMissing() throws Exception {
        // 只有 memory:read 的主体去读画像资源：scope 判定复用服务层的同一套规则，不在 MCP 侧放宽
        JsonNode body = readWithScopes("pwt://profile/identity", Scope.MEMORY_READ);

        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("SCOPE_REQUIRED");
        assertThat(body.get("error").get("message").asText()).contains(Scope.PROFILE_READ);
    }

    @Test
    void shouldReadFixedResourceAsJsonEnvelope() throws Exception {
        when(governanceQueryService.approvedStyleRules(any())).thenReturn(List.of());

        JsonNode body = read("pwt://profile/style");

        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("data").isArray()).isTrue();
    }

    @Test
    void shouldReadRecentWorkLogsThroughServiceVisibility() throws Exception {
        when(workLogService.list(any(), isNull(), isNull(), isNull(), any(PageQuery.class)))
            .thenReturn(PageResult.of(List.of(), 1, 20, 0L));

        JsonNode body = read("pwt://work/recent");

        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("data").get("items").isArray()).isTrue();
        // 不在 MCP 侧另写一套可见性判定：走服务层的 list，Agent 自然读不到 private
        verify(workLogService).list(any(ActorContext.class), isNull(), isNull(), isNull(),
            any(PageQuery.class));
    }

    @Test
    void shouldRejectRecentWorkLogsWhenScopeMissing() throws Exception {
        JsonNode body = readWithScopes("pwt://work/recent", Scope.MEMORY_READ);

        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asText()).isEqualTo("SCOPE_REQUIRED");
        assertThat(body.get("error").get("message").asText()).contains(Scope.WORK_READ);
    }

    private JsonNode read(String uri) throws Exception {
        return readWithScopes(uri, Scope.PROFILE_READ, Scope.MEMORY_READ, Scope.WORK_READ);
    }

    private JsonNode readWithScopes(String uri, String... scopes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(McpActorFilter.ATTRIBUTE_ACTOR,
            ActorContext.agent(userId, UUID.randomUUID(), Set.of(scopes)));
        McpTransportContext context =
            new McpContext().extract(request, new DefaultMcpTransportContext());
        McpSyncServerExchange exchange = new McpSyncServerExchange(new McpAsyncServerExchange(
            "s", null, new McpSchema.ClientCapabilities(Map.of(), null, null, null),
            new McpSchema.Implementation("test", "0"), context));
        SyncResourceSpecification spec = catalog.resources().get(uri);
        assertThat(spec).as("未注册的资源 URI: " + uri).isNotNull();

        McpSchema.ReadResourceResult result =
            spec.readHandler().apply(exchange, new McpSchema.ReadResourceRequest(uri));
        McpSchema.TextResourceContents contents =
            (McpSchema.TextResourceContents) result.contents().get(0);
        assertThat(contents.uri()).isEqualTo(uri);
        return MAPPER.readTree(contents.text());
    }
}
