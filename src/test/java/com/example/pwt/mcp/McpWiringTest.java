package com.example.pwt.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

/**
 * MCP 装配测试：开关生效与注册路径正确。
 *
 * <p>Servlet 必须挂在配置的端点上：传输提供者本身就是一个 {@code HttpServlet}，
 * 只声明成普通 Bean 的话 Boot 会给它兜底注册到 {@code /}，等于把整站路径交给 MCP 处理，
 * REST 接口全部失效而单元测试看不出来。
 *
 * @author pwt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    // 本测试在 mcp 包下，沿包路径找不到启动类，必须显式指定
    classes = com.example.pwt.app.PwtApplication.class,
    properties = {
        "spring.flyway.enabled=false",
        "pwt.bootstrap.enabled=false",
        "pwt.task.enabled=false",
        "pwt.mcp.enabled=true",
        "pwt.mcp.endpoint=/mcp",
        "pwt.identity.owner-jwt-secret=mcp-wiring-test-secret-key-long-enough-32"
    })
class McpWiringTest {

    @Autowired
    private McpSyncServer mcpServer;

    @Autowired
    private ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servletRegistration;

    @Autowired
    private FilterRegistrationBean<McpActorFilter> filterRegistration;

    @Test
    void shouldStartWithServerBeans() {
        assertThat(mcpServer).isNotNull();
        assertThat(mcpServer.getServerInfo().name()).isEqualTo("personal-work-twin");
    }

    @Test
    void shouldRegisterServletOnConfiguredEndpointOnly() {
        assertThat(servletRegistration.getUrlMappings()).containsExactly("/mcp");
        assertThat(servletRegistration.getServlet()).isNotNull();
    }

    @Test
    void shouldProtectEndpointWithAuthenticationFilter() {
        // 拦截器对独立 Servlet 不生效，认证只能靠这个 Filter；漏注册等于 MCP 全裸
        assertThat(filterRegistration.getFilter()).isNotNull();
        assertThat(filterRegistration.getUrlPatterns()).contains("/mcp", "/mcp/*");
        assertThat(filterRegistration.getOrder()).isLessThan(0);
    }
}
