package com.example.pwt.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 匿名公开入口的鉴权接线测试。
 *
 * <p>只断言“是否被认证拦截器拦下”，不依赖数据库中的具体数据：
 * 公开路径必须能通过拦截器，之后无论返回 200、404 还是数据库异常都不影响该结论；
 * 而受保护路径必须在进入业务逻辑前就返回 401。
 *
 * @author pwt
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "pwt.bootstrap.enabled=false",
    "pwt.identity.owner-jwt-secret=public-endpoint-test-secret-long-enough-32"
})
@AutoConfigureMockMvc
class PublicEndpointAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicPathsShouldNotRequireCredential() throws Exception {
        String userId = UUID.randomUUID().toString();
        // 公开入口不得返回 401；其余状态码取决于数据与数据库可用性
        for (String path : new String[]{
            "/public/" + userId + "/profile",
            "/public/" + userId + "/resume",
            "/public/" + userId + "/exists"}) {
            int status = mockMvc.perform(get(path))
                .andReturn().getResponse().getStatus();
            if (status == 401) {
                throw new AssertionError(path + " 不应要求认证，却返回 401");
            }
        }
    }

    @Test
    void protectedPathsShouldStillRequireCredential() throws Exception {
        mockMvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/resumes/current")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUserIdShouldBeRejectedBeforeDatabaseAccess() throws Exception {
        mockMvc.perform(get("/public/not-a-uuid/resume")).andExpect(status().isBadRequest());
    }
}
