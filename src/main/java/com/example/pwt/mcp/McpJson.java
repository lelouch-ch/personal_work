package com.example.pwt.mcp;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * MCP 侧的 JSON 序列化：把工具结果与资源内容写成文本。
 *
 * <p>用独立的 mapper 而不是复用 Spring MVC 那个：MCP 的输出是给 Agent 读的正文字符串，
 * 与 REST 的响应序列化互不影响，避免某一侧调整序列化配置时悄悄改变另一侧的输出。
 *
 * <p>时间必须显式配置：工具输出直接复用 REST 的 DTO，里面全是 {@code Instant}，
 * 裸 {@code ObjectMapper} 没有 JavaTime 模块会直接抛 InvalidDefinitionException；
 * 关闭时间戳输出是为了与 REST 保持一致的 ISO-8601 字符串格式。
 *
 * @author pwt
 */
final class McpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private McpJson() {
    }

    /**
     * 序列化为 JSON 文本。
     *
     * @param value 任意可序列化对象
     * @return JSON 文本
     * @throws BizException 序列化失败，按内部错误处理而不是让协议层抛裸异常
     */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "工具结果序列化失败", e);
        }
    }
}
