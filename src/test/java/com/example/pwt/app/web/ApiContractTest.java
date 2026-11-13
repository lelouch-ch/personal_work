package com.example.pwt.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.example.pwt.identity.application.OwnerTokenService;

/**
 * HTTP 契约测试：统一响应结构、错误码映射与鉴权门禁。
 *
 * <p>不访问数据库：所有用例都在进入持久层之前被拦截或校验拒绝。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.identity.owner-jwt-secret=contract-test-secret-key-long-enough-32-char"
})
@AutoConfigureMockMvc
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Test
    void shouldRejectMissingCredentialWith401AndUnifiedBody() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void shouldRejectUnparsableTokenWith401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami").header("Authorization", "Bearer not.a.jwt"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void shouldReturnFieldLevelDetailsOnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[?(@.field == 'username')]").exists())
            .andExpect(jsonPath("$.details[?(@.field == 'password')]").exists());
    }

    @Test
    void shouldTreatMalformedBodyAsClientError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRequireCredentialForAgentClientCreation() throws Exception {
        // 写接口同样在入口处要求凭证；scope 越权用例随 M3 的写接口一并覆盖
        mockMvc.perform(post("/api/v1/agent-clients")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientName\":\"x\",\"scopes\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldExposeRequestIdHeaderOnEveryResponse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/whoami"))
            .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void shouldReuseClientSuppliedRequestId() throws Exception {
        String requestId = "01a1d3f0-0000-7000-8000-000000000001";
        mockMvc.perform(get("/api/v1/auth/whoami").header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void shouldPrefer401Over404ForUnknownPath() throws Exception {
        // 未认证时先返回 401，避免向匿名请求泄露路由存在性
        mockMvc.perform(get("/api/v1/does-not-exist"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldResolveWhoamiForValidOwnerToken() throws Exception {
        String token = ownerTokenService.issue(java.util.UUID.randomUUID()).token();

        mockMvc.perform(get("/api/v1/auth/whoami").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.actorType").value("OWNER"))
            .andExpect(jsonPath("$.data.agentClientId").isEmpty())
            .andExpect(jsonPath("$.data.userId").isNotEmpty());
    }

}
