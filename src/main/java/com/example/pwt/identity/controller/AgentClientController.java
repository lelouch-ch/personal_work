package com.example.pwt.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.application.AgentClientService;
import com.example.pwt.identity.controller.dto.AgentClientCreateRequest;
import com.example.pwt.identity.controller.dto.AgentClientCreatedResponse;
import com.example.pwt.identity.controller.dto.AgentClientResponse;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.infrastructure.AgentClientEntity;
import com.example.pwt.identity.infrastructure.AgentClientMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 接入管理（设计文档 10.2）。
 *
 * <p>M1 只提供创建与撤销；列表分页随 M2 的 site 查询一并补齐。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/agent-clients")
public class AgentClientController {

    /** 明文密钥只返回一次的提示文案。 */
    private static final String TOKEN_WARNING = "accessToken 仅此一次返回，请立即妥善保存；遗失后需撤销并重新签发";

    private final AgentClientService agentClientService;

    private final AgentClientMapper agentClientMapper;

    /**
     * 构造控制器。
     *
     * @param agentClientService  客户端服务
     * @param agentClientMapper   客户端 Mapper
     */
    public AgentClientController(AgentClientService agentClientService, AgentClientMapper agentClientMapper) {
        this.agentClientService = agentClientService;
        this.agentClientMapper = agentClientMapper;
    }

    /**
     * 创建 Agent 客户端并签发凭证。
     *
     * @param actor   当前主体
     * @param request 创建请求
     * @return 客户端信息与明文密钥
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AgentClientCreatedResponse>> create(@CurrentActor ActorContext actor,
        @Valid @RequestBody AgentClientCreateRequest request) {
        AgentClientService.CreatedClient created = agentClientService.create(
            actor.userId(), request.clientName(), request.clientType(), request.scopes(), request.expiresAt());
        AgentClientCreatedResponse data = new AgentClientCreatedResponse(created.clientId(),
            created.clientName(), created.scopes(), created.expiresAt(), created.plainToken(),
            created.tokenPreview(), TOKEN_WARNING);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 撤销 Agent 客户端凭证，撤销后 MCP 与 REST 请求立即失效。
     *
     * @param actor    当前主体
     * @param clientId 客户端 ID
     * @return 空响应
     */
    @PostMapping("/{clientId}/revoke")
    public ResponseEntity<ApiResponse<Void>> revoke(@CurrentActor ActorContext actor,
                                                   @PathVariable UUID clientId) {
        agentClientService.revoke(actor.userId(), clientId);
        return ResponseEntity.ok(ApiResponse.ok(null, RequestContext.requestId()));
    }

    /**
     * 列出当前用户的 Agent 客户端。
     *
     * @param actor 当前主体
     * @return 客户端列表
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<AgentClientResponse>>> list(@CurrentActor ActorContext actor) {
        List<AgentClientEntity> entities = agentClientMapper.selectList(new LambdaQueryWrapper<AgentClientEntity>()
            .eq(AgentClientEntity::getUserId, actor.userId().toString())
            .orderByDesc(AgentClientEntity::getCreatedAt));
        List<AgentClientResponse> items = entities.stream().map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.ok(
            PageResult.of(items, 1, items.size(), items.size()), RequestContext.requestId()));
    }

    private AgentClientResponse toResponse(AgentClientEntity entity) {
        List<String> scopes = entity.getScopes() == null ? List.of() : List.copyOf(entity.getScopes());
        return new AgentClientResponse(UUID.fromString(entity.getId()), entity.getClientName(), scopes,
            entity.getStatus(), entity.getExpiresAt(), entity.getLastSeenAt(), entity.getCreatedAt());
    }
}
