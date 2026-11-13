package com.example.pwt.app.config;

import com.example.pwt.app.web.AuthenticationInterceptor;
import com.example.pwt.app.web.CurrentActorArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：认证拦截范围与主体参数解析。
 *
 * @author pwt
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 免认证路径：Actuator 探针与登录入口。新增公开端点必须显式登记在此。 */
    private static final String[] PUBLIC_PATHS = {
        "/actuator/**", "/api/v1/auth/login", "/error"
    };

    private final AuthenticationInterceptor authenticationInterceptor;

    private final CurrentActorArgumentResolver currentActorArgumentResolver;

    /**
     * 构造装配。
     *
     * @param authenticationInterceptor     认证拦截器
     * @param currentActorArgumentResolver  主体参数解析器
     */
    public WebMvcConfig(AuthenticationInterceptor authenticationInterceptor,
                        CurrentActorArgumentResolver currentActorArgumentResolver) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.currentActorArgumentResolver = currentActorArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentActorArgumentResolver);
    }
}
