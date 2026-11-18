package com.example.pwt.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pwt.identity.application.OwnerTokenService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 上下文接口的契约测试（设计文档 9.4 {@code search_memories} / {@code build_context}）。
 *
 * <p>用例都设计为在进入持久层之前结束，因此不要求 MySQL 可用；
 * 召回、评分与预算分配的行为由 {@code ContextComposerTest} 与 {@code ContextBuilderServiceTest} 覆盖。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.task.enabled=false",
    "pwt.identity.owner-jwt-secret=context-contract-test-secret-long-enough"
})
@AutoConfigureMockMvc
class ContextApiContractTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Test
    void shouldRejectUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/context/memories/search").param("q", "上传"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/context/build").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void searchShouldRequireQueryParameter() throws Exception {
        mockMvc.perform(get("/api/v1/context/memories/search").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void buildShouldRequireTask() throws Exception {
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[?(@.field == 'task')]").exists());
    }

    @Test
    void buildShouldRejectUnknownMemoryType() throws Exception {
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON)
                .content("{\"task\":\"修复上传问题\",\"includeTypes\":[\"not_a_type\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void buildShouldRejectTokenBudgetOutsideSchemaRange() throws Exception {
        // 9.4 的 Schema：500 ≤ token_budget ≤ 20000，越界必须在触达数据库之前拒绝
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON).content("{\"task\":\"修复上传问题\",\"tokenBudget\":10}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON).content("{\"task\":\"修复上传问题\",\"tokenBudget\":999999}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void buildShouldRejectOverlongTask() throws Exception {
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON).content("{\"task\":\"" + "字".repeat(5001) + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedBodyShouldBeClientError() throws Exception {
        mockMvc.perform(post("/api/v1/context/build").header("Authorization", ownerToken())
                .contentType(JSON).content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unparsableProjectIdShouldBeClientError() throws Exception {
        mockMvc.perform(get("/api/v1/context/memories/search").header("Authorization", ownerToken())
                .param("q", "上传").param("projectId", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    private String ownerToken() {
        return "Bearer " + ownerTokenService.issue(UUID.randomUUID()).token();
    }
}
