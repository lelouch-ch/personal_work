package com.example.pwt.common.web;

/**
 * 请求级上下文：保存 requestId，供响应体与日志 MDC 使用。
 *
 * @author pwt
 */
public final class RequestContext {

    /**
     * MDC 中 requestId 的键名，日志模板通过 {@code %X{requestId}} 引用。
     */
    public static final String MDC_REQUEST_ID = "requestId";

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    /**
     * 绑定当前请求的 requestId。
     *
     * @param requestId 请求标识
     */
    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    /**
     * 获取当前请求的 requestId。
     *
     * @return requestId，未绑定时返回 {@code null}
     */
    public static String requestId() {
        return REQUEST_ID.get();
    }

    /**
     * 清理线程变量，必须在请求结束时调用，避免线程复用导致串线。
     */
    public static void clear() {
        REQUEST_ID.remove();
    }
}
