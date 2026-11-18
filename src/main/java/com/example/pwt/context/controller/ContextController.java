package com.example.pwt.context.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.context.application.ContextBuilderService;
import com.example.pwt.context.application.ContextSearchService;
import com.example.pwt.context.controller.dto.ContextBuildRequest;
import com.example.pwt.context.controller.dto.ContextPacketResponse;
import com.example.pwt.context.controller.dto.MemorySearchResponse;
import com.example.pwt.identity.domain.ActorContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上下文接口：{@code search_memories} 与 {@code build_context} 的 REST 形态
 * （设计文档 9.3、9.4、11）。
 *
 * <p>设计文档 10.2 的接口表没有列这两条，因为它们是 MCP Tool。这里先以 REST 落地：
 * M6 的 MCP Server 只是把同一份服务方法包一层 Tool 协议，鉴权、scope 与审计都已在
 * 服务层做完，届时不产生第二套业务逻辑。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextController {

    private final ContextBuilderService contextBuilderService;

    private final ContextSearchService contextSearchService;

    /**
     * 构造控制器。
     *
     * @param contextBuilderService 上下文构建服务
     * @param contextSearchService  记忆检索服务
     */
    public ContextController(ContextBuilderService contextBuilderService,
                             ContextSearchService contextSearchService) {
        this.contextBuilderService = contextBuilderService;
        this.contextSearchService = contextSearchService;
    }

    /**
     * 为当前任务构建上下文包。
     *
     * @param actor   当前主体，Agent 需 {@code context:build}
     * @param request 任务与预算
     * @return 上下文包
     */
    @PostMapping("/build")
    public ResponseEntity<ApiResponse<ContextPacketResponse>> build(@CurrentActor ActorContext actor,
        @Valid @RequestBody ContextBuildRequest request) {
        ContextPacketResponse data = ContextPacketResponse.from(contextBuilderService.build(actor,
            request.task(), request.projectId(), request.tokenBudget(), request.includeTypes(),
            request.includeSkills()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 检索已批准记忆。
     *
     * @param actor     当前主体，Agent 需 {@code memory:read}
     * @param q         关键词或自然语言短语
     * @param types     类型过滤，可重复传参
     * @param projectId 项目过滤
     * @param limit     返回上限，1~50
     * @return 命中结果与召回路径
     */
    @GetMapping("/memories/search")
    public ResponseEntity<ApiResponse<MemorySearchResponse>> search(@CurrentActor ActorContext actor,
        @RequestParam String q, @RequestParam(required = false) List<String> types,
        @RequestParam(required = false) UUID projectId, @RequestParam(required = false) Integer limit) {
        MemorySearchResponse data = MemorySearchResponse.from(
            contextSearchService.search(actor, q, types, projectId, limit));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }
}
