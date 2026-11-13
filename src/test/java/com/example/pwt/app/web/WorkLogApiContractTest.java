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
 * 工作记录接口的契约测试。
 *
 * <p>用例都设计为在进入持久层之前结束，因此不要求 MySQL 可用：
 * 鉴权由拦截器拦截，参数校验由 Bean Validation 与服务的入参检查拒绝，
 * 索引与幂等约束的真实行为由 {@code MysqlDdlMigrationTest} 覆盖。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.identity.owner-jwt-secret=worklog-contract-test-secret-long-enough-32"
})
@AutoConfigureMockMvc
class WorkLogApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Test
    void shouldRejectUnauthenticatedWrite() throws Exception {
        mockMvc.perform(post("/api/v1/work-logs").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectUnauthenticatedRead() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReportFieldLevelErrorsForEmptyBody() throws Exception {
        mockMvc.perform(post("/api/v1/work-logs").header("Authorization", ownerToken())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[?(@.field == 'title')]").exists())
            .andExpect(jsonPath("$.details[?(@.field == 'occurredAt')]").exists());
    }

    @Test
    void shouldRejectOverLongTitle() throws Exception {
        String body = "{\"title\":\"" + "标".repeat(301) + "\",\"occurredAt\":\"2026-11-13T01:30:00Z\"}";

        mockMvc.perform(post("/api/v1/work-logs").header("Authorization", ownerToken())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'title')]").exists());
    }

    @Test
    void shouldRejectOversizedPaging() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs").header("Authorization", ownerToken())
                .param("size", "500"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMalformedLogIdBeforeDatabaseAccess() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs/not-a-uuid").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectBlankTagSearchBeforeDatabaseAccess() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs/search/by-tag").header("Authorization", ownerToken())
                .param("tag", "  "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectBlankKeywordSearchBeforeDatabaseAccess() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs/search/by-keyword").header("Authorization", ownerToken())
                .param("keyword", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingTagParameter() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs/search/by-tag").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectNonNumericLimit() throws Exception {
        mockMvc.perform(get("/api/v1/work-logs/search/by-tag").header("Authorization", ownerToken())
                .param("tag", "asr").param("limit", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String ownerToken() {
        return "Bearer " + ownerTokenService.issue(UUID.randomUUID()).token();
    }
}
