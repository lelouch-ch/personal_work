package com.example.pwt.llm;

import com.example.pwt.llm.LlmContract.ChatRequest;
import com.example.pwt.llm.LlmContract.ChatResult;

/**
 * 未配置 LLM 时的占位实现。
 *
 * <p>明确失败而不是返回空结果：空结果会让“提取到 0 条候选”看起来像正常业务结论，
 * 掩盖服务尚未接入模型这一事实。
 *
 * @author pwt
 */
public class NotConfiguredLlmProvider implements LlmProvider {

    /** 未配置时的原因说明。 */
    public static final String REASON = "未配置 LLM Provider（缺少 pwt.llm.endpoint / PWT_LLM_API_KEY）";

    @Override
    public String providerCode() {
        return "none";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        throw new LlmUnavailableException(REASON);
    }
}
