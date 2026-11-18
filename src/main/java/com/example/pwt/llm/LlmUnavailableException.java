package com.example.pwt.llm;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;

/**
 * LLM 调用失败或提供方未配置。
 *
 * <p>映射为 {@link ErrorCode#LLM_PROVIDER_ERROR}，在异步任务里表现为一次可重试的失败，
 * 不影响已保存的工作记录（设计文档 19.2 用例 14）。
 *
 * @author pwt
 */
public class LlmUnavailableException extends BizException {

    /**
     * 构造异常。
     *
     * @param message 失败原因，不得包含 API Key
     */
    public LlmUnavailableException(String message) {
        super(ErrorCode.LLM_PROVIDER_ERROR, message);
    }

    /**
     * 构造异常。
     *
     * @param message 失败原因
     * @param cause   根因
     */
    public LlmUnavailableException(String message, Throwable cause) {
        super(ErrorCode.LLM_PROVIDER_ERROR, message, cause);
    }
}
