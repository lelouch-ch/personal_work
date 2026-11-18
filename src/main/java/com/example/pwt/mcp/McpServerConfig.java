package com.example.pwt.mcp;

import com.example.pwt.context.application.ContextBuilderService;
import com.example.pwt.context.application.ContextSearchService;
import com.example.pwt.context.application.GovernanceQueryService;
import com.example.pwt.identity.application.CredentialResolver;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.work.application.WorkLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 服务端装配（设计文档 9.1、ADR-006）。
 *
 * <p>三件套：认证过滤器 → 传输 Servlet → 协议服务器。
 *
 * <p>用 Filter 而不是 Spring MVC 拦截器做认证：MCP 端点是独立注册的 Servlet，
 * 拦截器只挂在 MVC 的 HandlerMapping 上，对它完全不生效。
 *
 * <p>传输提供者本身就是一个 {@code HttpServlet}，必须显式用
 * {@link ServletRegistrationBean} 注册到配置的路径：只把它声明成普通 Bean 的话，
 * Spring Boot 会给它兜底注册到 {@code /}，等于把整站路径都交给 MCP 处理。
 *
 * <p>{@code McpServer.sync(...).build()} 会回调传输提供者的 {@code setSessionFactory}，
 * 因此两个 Bean 之间必须有依赖顺序：服务器 Bean 依赖传输提供者 Bean，
 * 不能反过来让传输层先于服务器就绪。
 *
 * @author pwt
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pwt.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

    /**
     * MCP 认证过滤器。
     *
     * @param credentialResolver 凭证解析器，与 REST 共用
     * @param objectMapper       写出错误响应用
     * @param properties         配置，决定要拦截的路径
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<McpActorFilter> mcpActorFilter(
        CredentialResolver credentialResolver, ObjectMapper objectMapper, McpProperties properties) {
        FilterRegistrationBean<McpActorFilter> registration =
            new FilterRegistrationBean<>(new McpActorFilter(credentialResolver, objectMapper));
        String endpoint = properties.getEndpoint();
        registration.addUrlPatterns(endpoint, endpoint + "/*");
        // 顺序设在最前：认证必须在进入协议处理之前完成
        registration.setOrder(-100);
        // SSE 需要异步：这里显式声明，不依赖框架默认值
        registration.setAsyncSupported(true);
        return registration;
    }

    /**
     * Streamable HTTP 传输提供者。
     *
     * @param objectMapper 复用应用的 mapper，保持与 REST 一致的时间与命名策略
     * @param properties   配置
     * @return 传输提供者
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
        ObjectMapper objectMapper, McpProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
            .objectMapper(objectMapper)
            .mcpEndpoint(properties.getEndpoint())
            .contextExtractor(new McpContext())
            .build();
    }

    /**
     * 把传输提供者挂到 {@code /mcp}。
     *
     * @param provider 传输提供者
     * @param properties 配置
     * @return Servlet 注册
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
        HttpServletStreamableServerTransportProvider provider, McpProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
            new ServletRegistrationBean<>(provider, properties.getEndpoint());
        // 传输层用 long-lived SSE 推流，必须开启异步支持
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * MCP 协议服务器：注册工具与资源。
     *
     * @param provider 传输提供者
     * @param tools    工具目录
     * @param resources 资源目录
     * @param properties 配置
     * @return 服务器；容器关闭时优雅退出
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider provider,
                                       McpToolCatalog tools, McpResourceCatalog resources,
                                       McpProperties properties) {
        return McpServer.sync(provider)
            .serverInfo(properties.getServerName(), properties.getServerVersion())
            .instructions("Personal Work Twin：个人工作记忆系统。"
                + "写入用 capture_work_log 与 propose_memory，"
                + "取用上下文用 build_context 与 search_memories。"
                + "记忆候选需由用户本人审核通过后才会进入上下文。")
            .capabilities(ServerCapabilities.builder()
                .tools(false)
                .resources(false, false)
                .build())
            .tools(tools.tools())
            .resources(resources.resources())
            .resourceTemplates(resources.resourceTemplates())
            .build();
    }

    /**
     * 工具目录。
     *
     * @param workLogService        工作记录服务
     * @param memoryService         记忆服务
     * @param contextSearchService  记忆检索
     * @param contextBuilderService 上下文构建
     * @param profileService        画像服务
     * @param resumeService         简历服务
     * @param properties            配置
     * @return 目录
     */
    @Bean
    public McpToolCatalog mcpToolCatalog(WorkLogService workLogService, MemoryService memoryService,
                                         ContextSearchService contextSearchService,
                                         ContextBuilderService contextBuilderService,
                                         ProfileService profileService, ResumeService resumeService,
                                         McpProperties properties) {
        return new McpToolCatalog(workLogService, memoryService, contextSearchService,
            contextBuilderService, profileService, resumeService, properties);
    }

    /**
     * 资源目录。
     *
     * @param profileService         画像服务
     * @param resumeService          简历服务
     * @param governanceQueryService 约束与风格规则查询
     * @param memoryService          记忆服务
     * @return 目录
     */
    @Bean
    public McpResourceCatalog mcpResourceCatalog(ProfileService profileService,
                                                 ResumeService resumeService,
                                                 GovernanceQueryService governanceQueryService,
                                                 MemoryService memoryService,
                                                 WorkLogService workLogService) {
        return new McpResourceCatalog(profileService, resumeService, governanceQueryService,
            memoryService, workLogService);
    }
}
