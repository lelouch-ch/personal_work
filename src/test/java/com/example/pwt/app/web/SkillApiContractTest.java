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
 * Skill 与工作风格接口的契约测试（设计文档 10.2 与 M7 的发布、审核动作）。
 *
 * <p>用例都设计为在进入持久层之前结束，因此不要求 MySQL 可用；
 * 版本语义与 Owner 专属判定的行为由 {@code SkillServiceTest} 覆盖。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.task.enabled=false",
    "pwt.identity.owner-jwt-secret=skill-contract-test-secret-long-enough"
})
@AutoConfigureMockMvc
class SkillApiContractTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnerTokenService ownerTokenService;

    @Test
    void shouldRejectUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/skills")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/skills").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/skills/" + UUID.randomUUID() + "/publish"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/style-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/style-rules").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createShouldReportFieldLevelErrorsForEmptyBody() throws Exception {
        mockMvc.perform(post("/api/v1/skills").header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.details[?(@.field == 'name')]").exists())
            .andExpect(jsonPath("$.details[?(@.field == 'contentMd')]").exists());
    }

    @Test
    void updateShouldRequireTheSameFields() throws Exception {
        mockMvc.perform(put("/api/v1/skills/" + UUID.randomUUID()).header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'contentMd')]").exists());
    }

    @Test
    void createShouldRejectOverlongContentBeforeDatabaseAccess() throws Exception {
        String body = "{\"slug\":\"code-review\",\"name\":\"代码评审\",\"contentMd\":\""
            + "内".repeat(30001) + "\"}";

        mockMvc.perform(post("/api/v1/skills").header("Authorization", ownerToken())
                .contentType(JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'contentMd')]").exists());
    }

    @Test
    void createShouldRejectTooManySourceMemoryIds() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            ids.append(i == 0 ? "" : ",").append("\"").append(UUID.randomUUID()).append("\"");
        }
        mockMvc.perform(post("/api/v1/skills").header("Authorization", ownerToken())
                .contentType(JSON)
                .content("{\"slug\":\"code-review\",\"name\":\"代码评审\",\"contentMd\":\"清单\","
                    + "\"sourceMemoryIds\":[" + ids + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'sourceMemoryIds')]").exists());
    }

    @Test
    void malformedSkillIdShouldBeClientError() throws Exception {
        mockMvc.perform(get("/api/v1/skills/not-a-uuid").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/skills/not-a-uuid/publish").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/skills/not-a-uuid/archive").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void skillsShouldRejectOversizedPaging() throws Exception {
        mockMvc.perform(get("/api/v1/skills").header("Authorization", ownerToken()).param("size", "500"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/skills").header("Authorization", ownerToken()).param("page", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void styleRuleShouldRequireKeyAndBoundedConfidence() throws Exception {
        mockMvc.perform(post("/api/v1/style-rules").header("Authorization", ownerToken())
                .contentType(JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[?(@.field == 'ruleKey')]").exists());
        // 置信度越界在触达数据库之前拒绝：它只是 Owner 的判据，不能写成越界值污染展示
        mockMvc.perform(post("/api/v1/style-rules").header("Authorization", ownerToken())
                .contentType(JSON).content("{\"ruleKey\":\"answer_format\",\"ruleValue\":\"先给结论\","
                    + "\"confidence\":1.5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("confidence")));
    }

    @Test
    void malformedStyleRuleIdShouldBeClientError() throws Exception {
        mockMvc.perform(post("/api/v1/style-rules/not-a-uuid/approve").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/style-rules/not-a-uuid/archive").header("Authorization", ownerToken()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedBodyShouldBeClientError() throws Exception {
        mockMvc.perform(post("/api/v1/skills").header("Authorization", ownerToken())
                .contentType(JSON).content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String ownerToken() {
        return "Bearer " + ownerTokenService.issue(UUID.randomUUID()).token();
    }
}