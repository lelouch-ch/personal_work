package com.example.pwt.llm;

import com.example.pwt.llm.LlmContract.ChatRequest;
import com.example.pwt.llm.LlmContract.ChatResult;
import com.example.pwt.llm.LlmContract.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容协议的 chat 实现（设计文档 12.1）。
 *
 * <p>兼容 /chat/completions 协议的自建网关与局域网模型可直接使用，
 * 只需改 endpoint 与 model，无需引入任何供应商 SDK。
 *
 * <p>用 JDK HttpClient 而不是 Spring RestClient：只依赖一次 POST 与一次 JSON 解析，
 * 少一个运行时依赖，也便于在离线环境用单测覆盖解析逻辑。
 *
 * @author pwt
 */
public class OpenAiCompatibleChatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProperties properties;

    private final HttpClient httpClient;

    /**
     * 构造 Provider。
     *
     * @param properties LLM 配置
     */
    public OpenAiCompatibleChatProvider(LlmProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
    }

    /**
     * 构造 Provider，供测试注入自定义客户端。
     *
     * @param properties LLM 配置
     * @param httpClient HTTP 客户端
     */
    public OpenAiCompatibleChatProvider(LlmProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public String providerCode() {
        return properties.providerCode();
    }

    @Override
    public boolean available() {
        return properties.configured();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        if (!available()) {
            throw new LlmUnavailableException(NotConfiguredLlmProvider.REASON);
        }
        String payload = buildPayload(request);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                return callOnce(request, payload);
            } catch (LlmUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                log.warn("LLM 调用失败, 准备重试 taskType={} attempt={} error={}",
                    request.taskType(), attempt + 1, e.getClass().getSimpleName());
            }
        }
        throw new LlmUnavailableException("LLM 调用失败: "
            + (last == null ? "未知原因" : last.getClass().getSimpleName()), last);
    }

    private ChatResult callOnce(ChatRequest request, String payload) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(chatCompletionsUrl(properties.endpoint())))
            .timeout(properties.timeout())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                // 只记录状态码与极短响应体，避免上游把请求头或密钥回显进错误体后被写进日志
                throw new LlmUnavailableException("LLM 返回状态码 " + response.statusCode()
                    + " " + truncate(response.body()));
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new LlmUnavailableException("LLM 调用网络异常: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM 调用被中断", e);
        }
    }

    private String buildPayload(ChatRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", properties.model());
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        } else if (properties.temperature() != null) {
            root.put("temperature", properties.temperature());
        }
        root.put("max_tokens", request.maxTokens() == null
            ? properties.maxOutputTokens() : request.maxTokens());
        if (request.jsonOutput()) {
            // 对结构化提取要求严格 JSON 输出（12.3）
            root.set("response_format", MAPPER.createObjectNode().put("type", "json_object"));
        }
        ArrayNode messages = root.putArray("messages");
        for (Message message : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        return root.toString();
    }

    /**
     * 解析响应体，取出首个候选的文本内容与用量。
     *
     * @param body 响应 JSON
     * @return 结果，promptVersion 由调用方回填
     */
    ChatResult parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmUnavailableException("LLM 响应缺少 choices");
            }
            JsonNode message = choices.get(0).path("message").path("content");
            if (message.isMissingNode() || message.isNull()) {
                throw new LlmUnavailableException("LLM 响应缺少 message.content");
            }
            JsonNode usage = root.path("usage");
            Map<String, Object> usageMap = usage.isObject()
                ? MAPPER.convertValue(usage, Map.class) : Map.of();
            return new ChatResult(message.asText(), root.path("model").asText(properties.model()),
                null, usageMap);
        } catch (IOException e) {
            throw new LlmUnavailableException("LLM 响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 拼接 chat/completions 地址，允许配置写成基地址或完整地址。
     *
     * @param endpoint 配置值
     * @return 完整地址
     */
    static String chatCompletionsUrl(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "…";
    }
}
