package com.example.pwt.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 记忆接口的契约测试。
 *
 * <p>用例都设计为在进入持久层之前结束，因此不要求 MySQL 可用；
 * 去重、冲突与审核状态机的数据库层面行为由 {@code MysqlDdlMigrationTest} 覆盖。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.task.enabled=false",
    "pwt.identity.owner-jwt-secret=memory-contract-test-secret-long-enough"
})
@AutoConfigureMockMvc
class MemoryApiContractTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Test
    void shouldRejectUnauthenticatedReadAndWrite() throws Exception {
        mockMvc.perform(get("/api/v1/memories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/memories/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/memories").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/memories/" + UUID.randomUUID() + "/approve").contentType(JSON)
            .content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReportFieldLevelErrorsForEmptyBody() throws Exception {
        mockMvc.perform(post("/api/v1/memories").header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[?(@.field == 'memoryType')]").exists())
            .andExpect(jsonPath("$.details[?(@.field == 'content')]").exists());
    }

    @Test
    void shouldRejectUnknownMemoryType() throws Exception {
        mockMvc.perform(post("/api/v1/memories").header("Authorization", ownerToken())
                .contentType(JSON).content("{\"memoryType\":\"rumour\",\"content\":\"内容\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("记忆类型")));
    }

    @Test
    void shouldRejectOverlongContent() throws Exception {
        String body = "{\"memoryType\":\"fact\",\"content\":\"" + "内".repeat(10001) + "\"}";

        mockMvc.perform(post("/api/v1/memories").header("Authorization", ownerToken())
                .contentType(JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'content')]").exists());
    }

    @Test
    void shouldRejectMalformedMemoryIdBeforeDatabaseAccess() throws Exception {
        mockMvc.perform(get("/api/v1/memories/not-a-uuid").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/memories/not-a-uuid/approve").header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRequireReplacementParameterForSupersede() throws Exception {
        mockMvc.perform(post("/api/v1/memories/" + UUID.randomUUID() + "/supersede")
                .header("Authorization", ownerToken()).contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectOversizedPaging() throws Exception {
        mockMvc.perform(get("/api/v1/memories").header("Authorization", ownerToken()).param("size", "500"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/memories/pending").header("Authorization", ownerToken()).param("page", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reviseShouldRequireContent() throws Exception {
        mockMvc.perform(put("/api/v1/memories/" + UUID.randomUUID()).header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'content')]").exists());
    }

    @Test
    void malformedBodyShouldBeClientError() throws Exception {
        mockMvc.perform(post("/api/v1/memories").header("Authorization", ownerToken())
                .contentType(JSON).content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String ownerToken() {
        return "Bearer " + ownerTokenService.issue(UUID.randomUUID()).token();
    }
}
