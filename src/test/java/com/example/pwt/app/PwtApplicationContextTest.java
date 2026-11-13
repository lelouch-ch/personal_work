package com.example.pwt.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.identity.application.AgentClientService;
import com.example.pwt.identity.application.OwnerTokenService;
import com.example.pwt.identity.application.UserAuthService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 应用上下文装配测试。
 *
 * <p>关闭 Flyway 且不访问数据库（HikariCP 惰性建连），用于在不依赖 MySQL 的前提下验证
 * 配置文件可解析、密钥校验生效、Bean 装配与接口路由注册正确。
 *
 * @author pwt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.flyway.enabled=false",
        "pwt.bootstrap.enabled=false",
        "pwt.identity.owner-jwt-secret=context-test-secret-key-long-enough-32-chars"
    })
class PwtApplicationContextTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserAuthService userAuthService;

    @Autowired
    private AgentClientService agentClientService;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Autowired
    private AuditRecorder auditRecorder;

    /** Actuator 也会注册一个 RequestMappingHandlerMapping 子类，必须按 Bean 名注入 MVC 的那一个。 */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor mybatisPlusInterceptor;

    @Test
    void shouldWireIdentityBeans() {
        assertThat(dataSource).isNotNull();
        assertThat(userAuthService).isNotNull();
        assertThat(agentClientService).isNotNull();
        assertThat(ownerTokenService).isNotNull();
        assertThat(auditRecorder).isNotNull();
    }

    @Test
    void shouldRegisterDocumentedEndpoints() {
        assertThat(handlerMapping.getHandlerMethods().keySet())
            .anySatisfy(info -> {
                assertThat(info.getPatternValues()).containsExactly("/api/v1/auth/login");
                assertThat(info.getMethodsCondition().getMethods()).containsExactly(
                    org.springframework.web.bind.annotation.RequestMethod.POST);
            })
            .anySatisfy(info -> assertThat(info.getPatternValues()).containsExactly("/api/v1/auth/whoami"))
            .anySatisfy(info -> assertThat(info.getPatternValues()).containsExactly("/api/v1/agent-clients"))
            .anySatisfy(info ->
                assertThat(info.getPatternValues()).containsExactly("/api/v1/agent-clients/{clientId}/revoke"))
            .anySatisfy(info -> {
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs");
                assertThat(info.getMethodsCondition().getMethods()).containsExactly(
                    org.springframework.web.bind.annotation.RequestMethod.POST);
            })
            .anySatisfy(info -> {
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs");
                assertThat(info.getMethodsCondition().getMethods()).containsExactly(
                    org.springframework.web.bind.annotation.RequestMethod.GET);
            })
            .anySatisfy(info ->
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs/{logId}"))
            .anySatisfy(info ->
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs/search/by-tag"))
            .anySatisfy(info ->
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs/search/by-keyword"))
            .anySatisfy(info ->
                assertThat(info.getPatternValues()).containsExactly("/api/v1/work-logs/{logId}/archive"));
    }

    @Test
    void shouldRegisterTenantInterceptorBeforePagination() {
        assertThat(mybatisPlusInterceptor.getInterceptors()).hasSize(2);
        assertThat(mybatisPlusInterceptor.getInterceptors().get(0))
            .isInstanceOf(com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor.class);
        assertThat(mybatisPlusInterceptor.getInterceptors().get(1))
            .isInstanceOf(com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor.class);
    }
}
