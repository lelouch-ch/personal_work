package com.example.pwt.work.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.work.application.WorkLogCommand;
import com.example.pwt.work.application.WorkLogService;
import com.example.pwt.work.controller.dto.WorkLogRequest;
import com.example.pwt.work.controller.dto.WorkLogResponse;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作记录接口（设计文档 10.2、10.3）。
 *
 * <p>写入同时服务业主手动录入与 Agent 的 {@code capture_work_log}，
 * 两者的差异只体现在凭证解析出的主体上下文与来源标记上。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/work-logs")
public class WorkLogController {

    /** 幂等键请求头（设计文档 10.1、17.3）。 */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WorkLogService workLogService;

    /**
     * 构造控制器。
     *
     * @param workLogService 工作记录服务
     */
    public WorkLogController(WorkLogService workLogService) {
        this.workLogService = workLogService;
    }

    /**
     * 创建工作记录。
     *
     * @param actor          当前主体，需 {@code work:write}
     * @param idempotencyKey 幂等键，可为空
     * @param request        记录内容
     * @return 新建记录；命中幂等键时返回既有记录，HTTP 仍为 201
     */
    @PostMapping
    public ResponseEntity<ApiResponse<WorkLogResponse>> create(@CurrentActor ActorContext actor,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
        @Valid @RequestBody WorkLogRequest request) {
        WorkLogCommand command = new WorkLogCommand(request.projectId(), request.title(), request.summary(),
            request.content(), request.workType(), request.occurredAt(), request.tags(),
            request.visibility(), idempotencyKey);
        WorkLogEntity entity = workLogService.create(actor, command);
        WorkLogResponse data = WorkLogResponse.from(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 分页查询工作记录。
     *
     * @param actor     当前主体，需 {@code work:read}
     * @param projectId 项目过滤
     * @param from      起始发生时间
     * @param to        截止发生时间
     * @param page      页码，从 1 开始
     * @param size      每页条数，最大 100
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<WorkLogResponse>>> list(@CurrentActor ActorContext actor,
        @RequestParam(required = false) UUID projectId, @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<WorkLogEntity> result = workLogService.list(actor, projectId, from, to, query);
        List<WorkLogResponse> items = result.items().stream().map(WorkLogResponse::from).toList();
        PageResult<WorkLogResponse> data = PageResult.of(items, result.page(), result.size(), result.total());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 按标签检索工作记录。
     *
     * @param actor 当前主体，需 {@code work:read}
     * @param tag   标签，大小写不敏感
     * @param limit 返回上限，最大 50
     * @return 命中记录
     */
    @GetMapping("/search/by-tag")
    public ResponseEntity<ApiResponse<List<WorkLogResponse>>> searchByTag(@CurrentActor ActorContext actor,
        @RequestParam String tag, @RequestParam(required = false, defaultValue = "20") int limit) {
        List<WorkLogResponse> data = workLogService.searchByTag(actor, tag, limit).stream()
            .map(WorkLogResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 按关键词全文检索工作记录。
     *
     * @param actor   当前主体，需 {@code work:read}
     * @param keyword 关键词，至少 2 个字符才能命中 ngram 分词
     * @param limit   返回上限，最大 50
     * @return 命中记录，按相关度倒序
     */
    @GetMapping("/search/by-keyword")
    public ResponseEntity<ApiResponse<List<WorkLogResponse>>> searchByKeyword(@CurrentActor ActorContext actor,
        @RequestParam String keyword, @RequestParam(required = false, defaultValue = "20") int limit) {
        List<WorkLogResponse> data = workLogService.searchByKeyword(actor, keyword, limit).stream()
            .map(WorkLogResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 查询工作记录详情。
     *
     * @param actor 当前主体，需 {@code work:read}
     * @param logId 记录 ID
     * @return 记录详情
     */
    @GetMapping("/{logId}")
    public ResponseEntity<ApiResponse<WorkLogResponse>> get(@CurrentActor ActorContext actor,
        @PathVariable UUID logId) {
        WorkLogResponse data = WorkLogResponse.from(workLogService.requireReadable(actor, logId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 调整记录可见性。
     *
     * @param actor      当前主体，需 {@code work:write}
     * @param logId      记录 ID
     * @param visibility 目标可见性，非 Owner 不得设为 public
     * @return 更新后的记录
     */
    @PutMapping("/{logId}/visibility")
    public ResponseEntity<ApiResponse<WorkLogResponse>> updateVisibility(@CurrentActor ActorContext actor,
        @PathVariable UUID logId, @RequestParam String visibility) {
        WorkLogResponse data = WorkLogResponse.from(workLogService.updateVisibility(actor, logId, visibility));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 归档记录。
     *
     * @param actor 当前主体，需 {@code work:write}
     * @param logId 记录 ID
     * @return 空响应
     */
    @PostMapping("/{logId}/archive")
    public ResponseEntity<ApiResponse<Void>> archive(@CurrentActor ActorContext actor,
        @PathVariable UUID logId) {
        workLogService.archive(actor, logId);
        return ResponseEntity.ok(ApiResponse.ok(null, RequestContext.requestId()));
    }
}
