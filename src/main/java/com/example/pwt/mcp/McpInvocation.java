package com.example.pwt.mcp;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * MCP 调用的执行外壳：绑定线程上下文、统一输出结构、把业务异常收敛成协议内错误
 * （设计文档 9.1、9.2、9.5、9.6）。
 *
 * <p>三件事各自的原因：
 * <ol>
 *   <li><b>绑定 {@link ActorContextHolder}</b>：多租户拦截器在取不到主体时<b>不追加</b>
 *       {@code user_id} 条件（见 {@code MybatisPlusConfig#ignoreTable}），
 *       而回调由 SDK 在复用的线程上调度，因此必须在每次调用开始时绑定、结束时恢复，
 *       否则会静默跨用户读写，并把主体串到下一个调用上。</li>
 *   <li><b>统一 {@code {ok,data,meta,error}}</b>：9.5 要求所有 Tool 同构，
 *       调用方才能用一套逻辑处理成功与失败；Resources 用同一结构，
 *       客户端读资源失败时也拿得到错误码而不是裸文本。</li>
 *   <li><b>业务异常不外抛</b>：scope 不足、参数非法是正常业务结果，
 *       抛出去会变成协议层故障，客户端只看到一条没有信息的 JSON-RPC error；
 *       改成 {@code isError=true} 并带上错误码与中文原因，Agent 才有机会自我修正。</li>
 * </ol>
 *
 * @author pwt
 */
final class McpInvocation {

    /** 资源与工具的 MIME 类型。 */
    static final String MIME_TYPE = "application/json";

    private static final Logger log = LoggerFactory.getLogger(McpInvocation.class);

    private McpInvocation() {
    }

    /**
     * 在已认证主体的上下文中执行一次工具调用。
     *
     * @param exchange 会话
     * @param name     工具名，仅用于日志
     * @param action   业务动作，返回值作为 {@code data}
     * @return 协议响应
     */
    static CallToolResult tool(McpSyncServerExchange exchange, String name, Supplier<Object> action) {
        Outcome outcome = invoke(exchange, name, action);
        return CallToolResult.builder()
            .addTextContent(envelope(outcome))
            .isError(outcome.failure() != null)
            .build();
    }

    /**
     * 在已认证主体的上下文中读取一个 Resource。
     *
     * @param exchange 会话
     * @param uri      响应里回显的资源 URI
     * @param name     资源名，仅用于日志
     * @param action   取数动作
     * @return 协议响应
     */
    static ReadResourceResult resource(McpSyncServerExchange exchange, String uri, String name,
                                       Supplier<Object> action) {
        Outcome outcome = invoke(exchange, name, action);
        // 资源读取没有 isError 字段可表达失败，统一用 ok=false + error 说明原因
        return new ReadResourceResult(List.of(
            new TextResourceContents(uri, MIME_TYPE, envelope(outcome))));
    }

    /**
     * 绑定上下文执行动作，返回成功数据或失败原因，不向外抛异常。
     *
     * @param exchange 会话
     * @param name     调用名，仅用于日志
     * @param action   动作
     * @return 执行结果
     */
    private static Outcome invoke(McpSyncServerExchange exchange, String name, Supplier<Object> action) {
        String requestId = requestId(exchange);
        ActorContext previousActor = ActorContextHolder.get();
        String previousRequestId = RequestContext.requestId();
        try {
            // 主体取自会话上下文：取不到就直接失败，绝不带着空主体去查询
            ActorContextHolder.set(McpContext.actor(exchange));
            RequestContext.setRequestId(requestId);
            MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
            return new Outcome(action.get(), null, requestId);
        } catch (BizException e) {
            log.info("MCP 调用业务失败 name={} code={} message={}", name, e.getErrorCode(), e.getMessage());
            return new Outcome(null, Failure.of(e.getErrorCode().name(), e.getMessage()), requestId);
        } catch (RuntimeException e) {
            // 非预期异常：只回一句通用提示，堆栈留在服务端日志里，不外泄实现细节
            log.error("MCP 调用异常 name={}", name, e);
            return new Outcome(null, Failure.of(ErrorCode.INTERNAL_ERROR.name(), "执行失败，请稍后重试"),
                requestId);
        } finally {
            restore(previousActor, previousRequestId);
        }
    }

    /**
     * 组装统一响应（设计文档 9.5）。
     *
     * <p>只给 text content 不给 {@code structuredContent}：SDK 在工具声明了
     * {@code outputSchema} 时会按该 schema 校验 {@code structuredContent}，
     * 而统一包装里的 {@code meta} 每次都变，要严格对齐就得给每个工具再写一份包装 schema。
     * 9.5 本身写的是「建议」，因此把包装 JSON 放进 text content，
     * 同时满足 9.1「提供简短 text content 以兼容客户端」。
     *
     * @param outcome 执行结果
     * @return 包装 JSON 文本
     */
    private static String envelope(Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", outcome.failure() == null);
        body.put("data", outcome.data());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("request_id", outcome.requestId());
        meta.put("generated_at", Instant.now().toString());
        body.put("meta", meta);
        body.put("error", outcome.failure() == null ? null : outcome.failure().body());
        return McpJson.write(body);
    }

    private static String requestId(McpSyncServerExchange exchange) {
        String requestId = McpContext.requestId(exchange);
        return requestId == null || requestId.isBlank() ? Uuids.randomUuidv7String() : requestId;
    }

    private static void restore(ActorContext previousActor, String previousRequestId) {
        if (previousActor == null) {
            ActorContextHolder.clear();
        } else {
            ActorContextHolder.set(previousActor);
        }
        MDC.remove(RequestContext.MDC_REQUEST_ID);
        if (previousRequestId == null) {
            RequestContext.clear();
        } else {
            RequestContext.setRequestId(previousRequestId);
        }
    }

    /**
     * 失败信息。
     *
     * @param body     写进响应的错误对象
     */
    private record Failure(Map<String, Object> body) {

        static Failure of(String code, String message) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            body.put("message", message);
            return new Failure(body);
        }
    }

    /**
     * 执行结果。
     *
     * @param data      成功时的数据
     * @param failure   失败原因，成功时为空
     * @param requestId 请求标识
     */
    private record Outcome(Object data, Failure failure, String requestId) {
    }
}
