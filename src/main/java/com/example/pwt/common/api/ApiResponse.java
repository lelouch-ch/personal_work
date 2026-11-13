package com.example.pwt.common.api;

import com.example.pwt.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * REST 统一响应体（设计文档 10.1）。
 *
 * @param code      错误码，成功为 {@code OK}
 * @param message   结果描述
 * @param data      业务数据，失败时为 {@code null}
 * @param requestId 请求链路标识
 * @param details   字段级校验等补充信息，可为空
 * @param <T>       业务数据类型
 * @author pwt
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data, String requestId, Object details) {

    /**
     * 构造成功响应。
     *
     * @param data      业务数据
     * @param requestId 请求标识
     * @param <T>       业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(ErrorCode.OK.name(), "success", data, requestId, null);
    }

    /**
     * 构造失败响应。
     *
     * @param errorCode 错误码
     * @param message   结果描述
     * @param requestId 请求标识
     * @param details   补充信息
     * @return 失败响应
     */
    public static ApiResponse<Void> fail(ErrorCode errorCode, String message, String requestId, Object details) {
        return new ApiResponse<>(errorCode.name(), message, null, requestId, details);
    }
}
