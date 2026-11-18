package com.example.pwt.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求与响应的中立契约（设计文档 12.1）。
 *
 * <p>业务模块只依赖本接口，不直接依赖任何供应商 SDK，避免供应商锁定（ADR-014）。
 *
 * @author pwt
 */
public final class LlmContract {

    private LlmContract() {
    }

    /**
     * 一条对话消息。
     *
     * @param role    角色：system / user / assistant
     * @param content 文本内容
     * @author pwt
     */
    public record Message(String role, String content) {

        /**
         * 构造系统消息。
         *
         * @param content 内容
         * @return 消息
         */
        public static Message system(String content) {
            return new Message("system", content);
        }

        /**
         * 构造用户消息。
         *
         * @param content 内容
         * @return 消息
         */
        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    /**
     * 对话请求。
     *
     * @param taskType    任务类型，用于选择模型与记录留痕
     * @param messages    对话消息，按顺序发送
     * @param jsonOutput  是否要求模型只返回 JSON 对象
     * @param maxTokens   输出上限，为空使用配置值
     * @param temperature 采样温度，为空使用配置值
     * @author pwt
     */
    public record ChatRequest(LlmTaskType taskType, List<Message> messages, boolean jsonOutput,
                              Integer maxTokens, Double temperature) {

        /**
         * 构造要求 JSON 输出的请求。
         *
         * @param taskType 任务类型
         * @param system   系统提示
         * @param user     用户内容
         * @return 请求
         */
        public static ChatRequest json(LlmTaskType taskType, String system, String user) {
            return new ChatRequest(taskType, List.of(Message.system(system), Message.user(user)),
                true, null, null);
        }
    }

    /**
     * 对话响应。
     *
     * @param content       模型输出文本
     * @param model         实际使用的模型名
     * @param promptVersion 提示词模板版本，由调用方回填
     * @param usage         token 用量，供应商未返回时为空
     * @author pwt
     */
    public record ChatResult(String content, String model, String promptVersion, Map<String, Object> usage) {

        @Override
        public String toString() {
            // content 可能包含用户正文，默认 record toString 会把整段写进日志
            return "ChatResult{model=" + model + ", promptVersion=" + promptVersion
                + ", contentLength=" + (content == null ? 0 : content.length()) + '}';
        }
    }
}
