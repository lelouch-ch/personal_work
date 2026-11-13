package com.example.pwt.common.error;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码，与 HTTP 状态码一一对应。
 *
 * <p>错误码取值与设计文档 9.5 一致，REST 与 MCP 共用同一套语义。
 *
 * @author pwt
 */
public enum ErrorCode {

    /** 请求成功。 */
    OK(HttpStatus.OK),
    /** 未认证或缺少有效凭证。 */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    /** 已认证但无权访问该资源。 */
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 凭证缺少所需 scope。 */
    SCOPE_REQUIRED(HttpStatus.FORBIDDEN),
    /** 资源不存在或不属于当前用户。 */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 参数校验失败。 */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    /** 幂等键冲突。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    /** 触发限流。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** 记忆仍在审核中，不可被 Agent 使用。 */
    MEMORY_PENDING_REVIEW(HttpStatus.CONFLICT),
    /** 记忆已被拒绝。 */
    MEMORY_REJECTED(HttpStatus.CONFLICT),
    /** LLM 供应商调用失败。 */
    LLM_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY),
    /** 同类任务正在执行。 */
    TASK_ALREADY_RUNNING(HttpStatus.CONFLICT),
    /** 服务端未预期异常。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    /**
     * 获取映射的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
