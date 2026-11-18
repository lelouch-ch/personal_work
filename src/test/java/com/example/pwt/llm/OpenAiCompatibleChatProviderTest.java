package com.example.pwt.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.llm.LlmContract.ChatResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * LLM Provider 配置选择与响应解析测试（设计文档 12.1、12.3）。
 *
 * <p>不发真实请求：只验证地址拼装、JSON 解析与“未配置时明确失败”的契约。
 *
 * @author pwt
 */
class OpenAiCompatibleChatProviderTest {

    private static LlmProperties props(String endpoint, String apiKey) {
        return new LlmProperties("test-provider", endpoint, "m1", apiKey,
            Duration.ofSeconds(5), 0, 512, 0.2d);
    }

    @Test
    void shouldAppendChatCompletionsPathOnlyOnce() {
        assertThat(OpenAiCompatibleChatProvider.chatCompletionsUrl("https://gw.local/v1"))
            .isEqualTo("https://gw.local/v1/chat/completions");
        assertThat(OpenAiCompatibleChatProvider.chatCompletionsUrl("https://gw.local/v1/"))
            .isEqualTo("https://gw.local/v1/chat/completions");
        assertThat(OpenAiCompatibleChatProvider.chatCompletionsUrl("https://gw.local/v1/chat/completions"))
            .isEqualTo("https://gw.local/v1/chat/completions");
    }

    @Test
    void shouldParseContentAndUsage() {
        OpenAiCompatibleChatProvider provider =
            new OpenAiCompatibleChatProvider(props("https://gw.local/v1", "sk-key"));

        ChatResult result = provider.parse("""
            {"model":"m1","choices":[{"message":{"content":"[]"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2}}""");

        assertThat(result.content()).isEqualTo("[]");
        assertThat(result.model()).isEqualTo("m1");
        assertThat(result.usage()).containsEntry("prompt_tokens", 10);
    }

    @Test
    void resultToStringShouldNotDumpWholeCompletion() {
        OpenAiCompatibleChatProvider provider =
            new OpenAiCompatibleChatProvider(props("https://gw.local/v1", "sk-key"));

        ChatResult result = provider.parse(
            "{\"choices\":[{\"message\":{\"content\":\"SECRET-CONTENT-HERE\"}}]}");

        assertThat(result.toString()).doesNotContain("SECRET-CONTENT-HERE").contains("contentLength=19");
    }

    @Test
    void missingChoicesShouldFailLoudly() {
        OpenAiCompatibleChatProvider provider =
            new OpenAiCompatibleChatProvider(props("https://gw.local/v1", "sk-key"));

        assertThatThrownBy(() -> provider.parse("{\"choices\":[]}"))
            .isInstanceOf(LlmUnavailableException.class)
            .hasMessageContaining("choices");
        assertThatThrownBy(() -> provider.parse("not json"))
            .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void unconfiguredProviderShouldReportUnavailable() {
        LlmProvider blank = new LlmProviderConfig().llmProvider(props("", ""));
        LlmProvider onlyEndpoint = new LlmProviderConfig().llmProvider(props("https://gw.local/v1", ""));
        LlmProvider configured = new LlmProviderConfig().llmProvider(props("https://gw.local/v1", "sk-key"));

        assertThat(blank.available()).isFalse();
        assertThat(onlyEndpoint.available()).isFalse();
        assertThat(configured.available()).isTrue();
        assertThatThrownBy(() -> blank.chat(LlmContract.ChatRequest.json(
            LlmTaskType.MEMORY_EXTRACTION, "s", "u")))
            .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void notConfiguredProviderMustNotPretendSuccess() {
        LlmProvider provider = new NotConfiguredLlmProvider();

        assertThat(provider.providerCode()).isEqualTo("none");
        assertThat(provider.available()).isFalse();
        assertThatThrownBy(() -> provider.chat(
            LlmContract.ChatRequest.json(LlmTaskType.MEMORY_EXTRACTION, "s", "u")))
            .isInstanceOf(LlmUnavailableException.class)
            .hasMessageContaining("未配置");
    }

    @Test
    void defaultsShouldBeFilled() {
        LlmProperties defaults = new LlmProperties(null, null, null, null, null, -1, 0, null);

        assertThat(defaults.providerCode()).isEqualTo("openai-compatible");
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(60));
        // 负数表示“未配置”，回落到默认 1 次重试，而不是照抄 -1
        assertThat(defaults.maxRetries()).isEqualTo(1);
        assertThat(defaults.maxOutputTokens()).isEqualTo(2000);
        assertThat(defaults.configured()).isFalse();
    }
}
