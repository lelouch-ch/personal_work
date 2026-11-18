package com.example.pwt.memory.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.application.MemoryProposeCommand;
import com.example.pwt.memory.application.MemoryService;
import com.example.pwt.memory.controller.dto.MemoryDetailResponse;
import com.example.pwt.memory.controller.dto.MemoryProposeRequest;
import com.example.pwt.memory.controller.dto.MemoryResponse;
import com.example.pwt.memory.controller.dto.MemoryReviewRequest;
import com.example.pwt.memory.controller.dto.MemoryReviseRequest;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆接口（设计文档 10.2、10.4）。
 *
 * <p>写侧只有一条通道：Owner 手动新建与 Agent 的 {@code propose_memory} 都落到
 * {@link MemoryService#propose}，因此风险分级、去重与审核规则对两种来源完全一致。
 *
 * <p>审核动作（批准、拒绝、归档、修正、替代）只允许 Owner，Agent 一律 403（19.2 用例 5）。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

    private final MemoryService memoryService;

    /**
     * 构造控制器。
     *
     * @param memoryService 记忆服务
     */
    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 提交记忆候选。
     *
     * @param actor   当前主体，Agent 需 {@code memory:propose}
     * @param request 候选内容
     * @return 落库后的记忆，状态由服务端审核策略决定
     */
    @PostMapping
    public ResponseEntity<ApiResponse<MemoryResponse>> propose(@CurrentActor ActorContext actor,
        @Valid @RequestBody MemoryProposeRequest request) {
        MemoryProposeCommand command = new MemoryProposeCommand(request.memoryType(), request.content(),
            request.summary(), request.evidence(), request.sourceWorkLogId(), request.visibility(),
            request.confidence(), request.expiresAt(), Map.of());
        MemoryResponse data = MemoryResponse.from(memoryService.propose(actor, command));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 分页查询记忆。
     *
     * @param actor  当前主体，需 {@code memory:read}
     * @param type   类型过滤
     * @param status 状态过滤
     * @param page   页码，从 1 开始
     * @param size   每页条数，最大 100
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<MemoryResponse>>> list(@CurrentActor ActorContext actor,
        @RequestParam(required = false) String type, @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<MemoryEntity> result = memoryService.list(actor, type, status, query);
        List<MemoryResponse> items = result.items().stream().map(MemoryResponse::from).toList();
        PageResult<MemoryResponse> data = PageResult.of(items, result.page(), result.size(), result.total());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 待审核候选列表，对应审核页入口（设计文档 10.2）。
     *
     * @param actor 当前主体，仅 Owner
     * @param page  页码
     * @param size  每页条数
     * @return 分页结果
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PageResult<MemoryResponse>>> pending(@CurrentActor ActorContext actor,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<MemoryEntity> result = memoryService.listPending(actor, query);
        List<MemoryResponse> items = result.items().stream().map(MemoryResponse::from).toList();
        PageResult<MemoryResponse> data = PageResult.of(items, result.page(), result.size(), result.total());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 记忆详情，含证据与审核留痕。
     *
     * @param actor    当前主体，需 {@code memory:read}
     * @param memoryId 记忆 ID
     * @return 详情
     */
    @GetMapping("/{memoryId}")
    public ResponseEntity<ApiResponse<MemoryDetailResponse>> detail(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId) {
        MemoryDetailResponse data = MemoryDetailResponse.from(memoryService.detail(actor, memoryId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 批准记忆。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param request  备注，可为空
     * @return 批准后的记忆
     */
    @PostMapping("/{memoryId}/approve")
    public ResponseEntity<ApiResponse<MemoryResponse>> approve(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId, @Valid @RequestBody(required = false) MemoryReviewRequest request) {
        MemoryResponse data = MemoryResponse.from(
            memoryService.approve(actor, memoryId, request == null ? null : request.comment()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 拒绝记忆。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param request  拒绝原因，可为空
     * @return 拒绝后的记忆
     */
    @PostMapping("/{memoryId}/reject")
    public ResponseEntity<ApiResponse<MemoryResponse>> reject(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId, @Valid @RequestBody(required = false) MemoryReviewRequest request) {
        MemoryResponse data = MemoryResponse.from(
            memoryService.reject(actor, memoryId, request == null ? null : request.comment()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 归档记忆。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @return 空响应
     */
    @PostMapping("/{memoryId}/archive")
    public ResponseEntity<ApiResponse<Void>> archive(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId) {
        memoryService.archive(actor, memoryId);
        return ResponseEntity.ok(ApiResponse.ok(null, RequestContext.requestId()));
    }

    /**
     * 修正记忆正文，可选修正后直接批准（设计文档 16.3）。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param request  新正文与是否批准
     * @return 生效的记忆
     */
    @PutMapping("/{memoryId}")
    public ResponseEntity<ApiResponse<MemoryResponse>> revise(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId, @Valid @RequestBody MemoryReviseRequest request) {
        MemoryResponse data = MemoryResponse.from(memoryService.revise(actor, memoryId, request.content(),
            request.summary(), Boolean.TRUE.equals(request.approve())));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 用一条候选替代既有记忆。
     *
     * @param actor       当前主体，仅 Owner
     * @param memoryId    被替代的记忆 ID
     * @param replacement 替代它的候选记忆 ID
     * @param request     备注，可为空
     * @return 替代后的新记忆
     */
    @PostMapping("/{memoryId}/supersede")
    public ResponseEntity<ApiResponse<MemoryResponse>> supersede(@CurrentActor ActorContext actor,
        @PathVariable UUID memoryId, @RequestParam UUID replacement,
        @Valid @RequestBody(required = false) MemoryReviewRequest request) {
        MemoryResponse data = MemoryResponse.from(memoryService.supersede(actor, memoryId, replacement,
            request == null ? null : request.comment()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }
}
