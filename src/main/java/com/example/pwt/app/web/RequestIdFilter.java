package com.example.pwt.app.web;

import com.example.pwt.common.util.Uuids;
import com.example.pwt.common.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求标识过滤器：为每个请求生成 requestId，写入响应头、日志 MDC 与线程上下文。
 *
 * <p>客户端可通过 {@code X-Request-Id} 传入以便串联链路。
 *
 * @author pwt
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    /** 请求标识头。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = Uuids.randomUuidv7String();
        }
        RequestContext.setRequestId(requestId);
        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.MDC_REQUEST_ID);
            RequestContext.clear();
        }
    }
}
