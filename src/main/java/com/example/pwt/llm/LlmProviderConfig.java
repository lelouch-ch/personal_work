package com.example.pwt.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM Provider 装配。
 *
 * <p>按配置选择实现：填了 endpoint 与 apiKey 才创建真实调用者，否则退化为“未配置”实现，
 * 让没有模型服务的开发环境也能完整跑通审核链路（提取任务会失败并重试，不伪造结果）。
 *
 * <p>用单个 Bean 方法内判定制，而不是两个 {@code @ConditionalOnMissingBean} 方法：
 * 后者在同一配置类内依赖方法求值顺序，容易在新增实现时静默失效。
 *
 * @author pwt
 */
@Configuration
public class LlmProviderConfig {

    /**
     * 选择 LLM 实现。
     *
     * @param properties LLM 配置
     * @return Provider
     */
    @Bean
    public LlmProvider llmProvider(LlmProperties properties) {
        return properties.configured()
            ? new OpenAiCompatibleChatProvider(properties) : new NotConfiguredLlmProvider();
    }
}
