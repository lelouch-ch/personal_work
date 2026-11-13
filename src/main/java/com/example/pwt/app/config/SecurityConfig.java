package com.example.pwt.app.config;

import com.example.pwt.identity.IdentityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证相关 Bean 装配。
 *
 * <p>MVP 不引入 Spring Security 过滤器链：Owner 走 JWT、Agent 走 API Key + scope，
 * 两套判定互不混用（设计文档 14.2），由 {@link com.example.pwt.app.web.AuthenticationInterceptor} 统一处理，
 * 这里只复用 Spring Security 的口令编码器。
 *
 * @author pwt
 */
@Configuration
public class SecurityConfig {

    /**
     * BCrypt 口令编码器，强度使用默认 10 轮。
     *
     * @return 口令编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 在启动阶段校验密钥配置，缺失或过短直接失败。
     *
     * @param properties 身份配置
     */
    public SecurityConfig(IdentityProperties properties) {
        properties.validate();
    }
}
