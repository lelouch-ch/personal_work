package com.example.pwt.common.error;

/**
 * 业务异常：由服务端规则或权限判定产生，携带统一错误码。
 *
 * <p>异常消息只用于日志与人工排查，不直接暴露跨用户资源的存在性（设计文档 14.3）。
 *
 * @author pwt
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * 使用指定错误码构造异常。
     *
     * @param errorCode 错误码
     * @param message   详细消息，仅用于日志与排查
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用指定错误码构造异常并携带原因。
     *
     * @param errorCode 错误码
     * @param message   详细消息
     * @param cause     根因
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 构造“资源不存在”异常，用于跨用户访问时不泄露资源存在性。
     *
     * @param resourceType 资源类型
     * @return 业务异常
     */
    public static BizException notFound(String resourceType) {
        return new BizException(ErrorCode.RESOURCE_NOT_FOUND, resourceType + " not found");
    }

    /**
     * 构造“缺少 scope”异常。
     *
     * @param scope 所需 scope
     * @return 业务异常
     */
    public static BizException scopeRequired(String scope) {
        return new BizException(ErrorCode.SCOPE_REQUIRED, "Missing required scope: " + scope);
    }
}
