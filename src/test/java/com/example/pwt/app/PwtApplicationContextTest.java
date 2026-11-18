package com.example.pwt.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.identity.application.AgentClientService;
import com.example.pwt.identity.application.OwnerTokenService;
import com.example.pwt.identity.application.UserAuthService;
import com.example.pwt.llm.LlmProvider;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.task.application.AsyncTaskWorker;
import com.example.pwt.task.application.TaskService;
import java.util.List;
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
        "pwt.task.enabled=false",
        // 显式清空，避免开发机上的 PWT_LLM_* 环境变量让“未配置 LLM”的断言随机失败
        "pwt.llm.endpoint=",
        "pwt.llm.api-key=",
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

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AsyncTaskWorker asyncTaskWorker;

    @Autowired
    private LlmProvider llmProvider;

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
        assertThat(memoryService).isNotNull();
        assertThat(taskService).isNotNull();
        assertThat(asyncTaskWorker).isNotNull();
        // 未配置 LLM 时必须装配出“未配置”实现，而不是让容器缺 Bean
        assertThat(llmProvider.available()).isFalse();
        assertThat(llmProvider.providerCode()).isEqualTo("none");
    }

    @Test
    void shouldRegisterDocumentedEndpoints() {
        java.util.Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .collect(java.util.stream.Collectors.toSet());

        // 设计文档 10.2 已实现的接口必须全部注册；漏注册会直接 404 而不是走业务校验
        assertThat(patterns).contains(
            "/api/v1/auth/login",
            "/api/v1/auth/whoami",
            "/api/v1/agent-clients",
            "/api/v1/agent-clients/{clientId}/revoke",
            "/api/v1/profile",
            "/api/v1/resumes/current",
            "/api/v1/resumes",
            "/api/v1/projects",
            "/api/v1/projects/{projectId}",
            "/api/v1/work-logs",
            "/api/v1/work-logs/{logId}",
            "/api/v1/work-logs/{logId}/archive",
            "/api/v1/work-logs/{logId}/visibility",
            "/api/v1/work-logs/search/by-tag",
            "/api/v1/work-logs/search/by-keyword",
            "/api/v1/memories",
            "/api/v1/memories/pending",
            "/api/v1/memories/{memoryId}",
            "/api/v1/memories/{memoryId}/approve",
            "/api/v1/memories/{memoryId}/reject",
            "/api/v1/memories/{memoryId}/archive",
            "/api/v1/memories/{memoryId}/supersede");
    }

    @Test
    void shouldRegisterWriteEndpointsWithPostOnly() {
        // 审核动作必须是 POST 且不能被 GET 覆盖，否则缓存与预取可能误触发状态迁移
        for (String path : List.of("/api/v1/memories/{memoryId}/approve", "/api/v1/memories/{memoryId}/reject",
            "/api/v1/memories/{memoryId}/archive", "/api/v1/memories/{memoryId}/supersede")) {
            assertThat(handlerMapping.getHandlerMethods().keySet())
                .as("%s 应只接受 POST", path)
                .anySatisfy(info -> {
                    assertThat(info.getPatternValues()).containsExactly(path);
                    assertThat(info.getMethodsCondition().getMethods()).containsExactly(
                        org.springframework.web.bind.annotation.RequestMethod.POST);
                });
        }
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
