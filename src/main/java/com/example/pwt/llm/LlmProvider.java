package com.example.pwt.llm;

import com.example.pwt.llm.LlmContract.ChatRequest;
import com.example.pwt.llm.LlmContract.ChatResult;

/**
 * LLM Provider 抽象（设计文档 12.1）。
 *
 * <p>MVP 只要求实现 {@link #chat(ChatRequest)}；Embedding 暂不使用，
 * 因此默认实现直接抛出不支持，避免出现“看起来可用、实际未接线”的空方法。
 *
 * @author pwt
 */
public interface LlmProvider {

    /**
     * 提供方标识，写入生成留痕。
     *
     * @return provider code
     */
    String providerCode();

    /**
     * 当前是否可用。不可用时任务应失败重试，而不是静默产出空结果（设计文档 19.2 用例 14）。
     *
     * @return true 表示可发起调用
     */
    boolean available();

    /**
     * 发起一次对话补全。
     *
     * @param request 请求
     * @return 响应
     * @throws LlmUnavailableException 提供方不可用或调用失败
     */
    ChatResult chat(ChatRequest request);

    /**
     * 计算文本向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     * @throws UnsupportedOperationException MVP 未实现
     */
    default float[][] embed(java.util.List<String> texts) {
        throw new UnsupportedOperationException("Embedding is not used in MVP");
    }
}
