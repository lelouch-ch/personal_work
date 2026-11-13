package com.example.pwt.common.web;

import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常统一映射为设计文档 10.1 的响应结构。
 *
 * <p>未预期异常只回错误码与 requestId，堆栈写日志，避免向外部泄露内部实现细节。
 *
 * @author pwt
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     *
     * @param e       业务异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("业务异常 uri={} code={}", request.getRequestURI(), code, e);
        } else {
            log.warn("业务异常 uri={} code={} message={}", request.getRequestURI(), code, e.getMessage());
        }
        return build(code, e.getMessage(), null);
    }

    /**
     * 处理请求体校验失败。
     *
     * @param e       校验异常
     * @param request 当前请求
     * @return 统一响应，details 为字段级错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e,
                                                             HttpServletRequest request) {
        List<Map<String, String>> details = new ArrayList<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", error.getField());
            item.put("message", error.getDefaultMessage());
            details.add(item);
        }
        log.warn("参数校验失败 uri={} fields={}", request.getRequestURI(), details);
        return build(ErrorCode.VALIDATION_ERROR, "请求参数校验失败", details);
    }

    /**
     * 处理方法级参数校验失败（如 {@code @RequestParam} 上的约束注解）。
     *
     * @param e       校验异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e,
                                                                   HttpServletRequest request) {
        log.warn("参数校验失败 uri={}", request.getRequestURI(), e);
        return build(ErrorCode.VALIDATION_ERROR, "请求参数校验失败", null);
    }

    /**
     * 处理缺失的必填请求参数。
     *
     * @param e       缺失参数异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e,
                                                               HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "缺少必填参数：" + e.getParameterName(), null);
    }

    /**
     * 处理请求体无法解析（JSON 格式错误、类型不匹配等）。属于客户端错误，不得回 5xx。
     *
     * @param e       解析异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        log.warn("请求体解析失败 uri={} message={}", request.getRequestURI(), e.getMessage());
        return build(ErrorCode.VALIDATION_ERROR, "请求体格式错误", null);
    }

    /**
     * 处理不支持的 Content-Type。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException e,
                                                            HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "不支持的 Content-Type", null);
    }

    /**
     * 处理不支持的请求方法。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                     HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "不支持的请求方法：" + e.getMethod(), null);
    }

    /**
     * 处理非法参数。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e,
                                                                  HttpServletRequest request) {
        log.warn("非法参数 uri={} message={}", request.getRequestURI(), e.getMessage());
        return build(ErrorCode.VALIDATION_ERROR, e.getMessage(), null);
    }

    /**
     * 处理未匹配到处理器的路径。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e,
                                                             HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "接口不存在", null);
    }

    /**
     * 兜底处理未预期异常。
     *
     * @param e       异常
     * @param request 当前请求
     * @return 统一响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期异常 uri={}", request.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR, "服务内部错误", null);
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, Object details) {
        HttpStatus status = code.getHttpStatus();
        ApiResponse<Void> body = ApiResponse.fail(code, message, RequestContext.requestId(), details);
        return ResponseEntity.status(status).body(body);
    }
}
