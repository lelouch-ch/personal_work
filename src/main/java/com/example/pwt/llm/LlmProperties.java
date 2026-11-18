package com.example.pwt.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM Provider 配置（设计文档 12.1）。
 *
 * <p>{@code apiKey} 只从环境变量注入，不写入数据库、不出现在日志中；
 * 这里保存的是引用值本身，配置项名称与 18.2 的环境变量清单对应。
 *
 * @param providerCode    提供方标识，用于留痕
 * @param endpoint        OpenAI 兼容的 chat/completions 基地址，为空表示未配置
 * @param model           模型名
 * @param apiKey          访问凭证，来自环境变量
 * @param timeout         单次调用超时
 * @param maxRetries      调用级重试次数（与任务级重试相互独立）
 * @param maxOutputTokens 输出上限
 * @param temperature     采样温度
 * @author pwt
 */
@ConfigurationProperties(prefix = "pwt.llm")
public record LlmProperties(
    String providerCode,
    String endpoint,
    String model,
    String apiKey,
    Duration timeout,
    int maxRetries,
    int maxOutputTokens,
    Double temperature) {

    /**
     * 补全未显式配置的默认值。
     */
    public LlmProperties {
        if (providerCode == null || providerCode.isBlank()) {
            providerCode = "openai-compatible";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxRetries < 0) {
            maxRetries = 1;
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 2000;
        }
    }

    /**
     * 判断是否已完成可用配置。
     *
     * @return true 表示 endpoint 与 apiKey 均已提供
     */
    public boolean configured() {
        return endpoint != null && !endpoint.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
