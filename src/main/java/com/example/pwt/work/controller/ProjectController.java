package com.example.pwt.work.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.work.application.ProjectService;
import com.example.pwt.work.controller.dto.ProjectRequest;
import com.example.pwt.work.controller.dto.ProjectResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目管理接口（设计文档 10.2）。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 构造控制器。
     *
     * @param projectService 项目服务
     */
    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建项目。
     *
     * @param actor   当前主体
     * @param request 项目内容
     * @return 新建项目
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@CurrentActor ActorContext actor,
        @Valid @RequestBody ProjectRequest request) {
        ProjectResponse data = ProjectResponse.from(projectService.create(actor, request.name(),
            request.description(), request.status(), request.visibility(), request.startedAt(),
            request.endedAt(), request.metadata()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 分页查询项目。
     *
     * @param actor  当前主体
     * @param status 状态过滤
     * @param page   页码，从 1 开始
     * @param size   每页条数，最大 100
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ProjectResponse>>> list(@CurrentActor ActorContext actor,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<com.example.pwt.work.infrastructure.ProjectEntity> result =
            projectService.list(actor, status, query);
        List<ProjectResponse> items = result.items().stream().map(ProjectResponse::from).toList();
        PageResult<ProjectResponse> data = PageResult.of(items, result.page(), result.size(), result.total());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 查询项目详情。
     *
     * @param actor     当前主体
     * @param projectId 项目 ID
     * @return 项目详情
     */
    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> get(@CurrentActor ActorContext actor,
        @PathVariable UUID projectId) {
        ProjectResponse data = ProjectResponse.from(projectService.requireReadable(actor, projectId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 更新项目。
     *
     * @param actor     当前主体
     * @param projectId 项目 ID
     * @param request   待更新字段
     * @return 更新后的项目
     */
    @PutMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> update(@CurrentActor ActorContext actor,
        @PathVariable UUID projectId, @Valid @RequestBody ProjectRequest request) {
        ProjectResponse data = ProjectResponse.from(projectService.update(actor, projectId, request.name(),
            request.description(), request.status(), request.visibility(), request.startedAt(),
            request.endedAt(), request.metadata()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 归档项目。
     *
     * @param actor     当前主体
     * @param projectId 项目 ID
     * @return 空响应
     */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<ApiResponse<Void>> archive(@CurrentActor ActorContext actor,
        @PathVariable UUID projectId) {
        projectService.archive(actor, projectId);
        return ResponseEntity.ok(ApiResponse.ok(null, RequestContext.requestId()));
    }
}
