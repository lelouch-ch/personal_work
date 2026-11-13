package com.example.pwt.profile.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.profile.controller.dto.ResumeCreateRequest;
import com.example.pwt.profile.controller.dto.ResumeResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 简历管理接口（设计文档 10.2）。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    /**
     * 构造控制器。
     *
     * @param resumeService 简历服务
     */
    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    /**
     * 获取当前简历。
     *
     * @param actor 当前主体
     * @return 当前简历
     */
    @GetMapping("/current")
    public ResponseEntity<ApiResponse<ResumeResponse>> current(@CurrentActor ActorContext actor) {
        ResumeResponse data = ResumeResponse.from(resumeService.readCurrent(actor));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 列出全部简历版本。
     *
     * @param actor 当前主体
     * @return 按版本倒序的简历列表
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ResumeResponse>>> list(@CurrentActor ActorContext actor) {
        List<ResumeResponse> data = resumeService.listVersions(actor).stream()
            .map(ResumeResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 新建简历版本。
     *
     * @param actor   当前主体
     * @param request 简历内容
     * @return 新建的简历
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ResumeResponse>> create(@CurrentActor ActorContext actor,
        @Valid @RequestBody ResumeCreateRequest request) {
        ResumeResponse data = ResumeResponse.from(resumeService.create(actor, request.title(),
            request.contentMd(), request.resumeJson(), request.visibility(), request.currentOrDefault()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 切换当前简历版本。
     *
     * @param actor    当前主体
     * @param resumeId 目标简历 ID
     * @return 切换后的当前简历
     */
    @PostMapping("/{resumeId}/current")
    public ResponseEntity<ApiResponse<ResumeResponse>> makeCurrent(@CurrentActor ActorContext actor,
        @PathVariable UUID resumeId) {
        ResumeResponse data = ResumeResponse.from(resumeService.makeCurrent(actor, resumeId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 调整简历可见性，即发布或撤回公开。
     *
     * @param actor    当前主体
     * @param resumeId 简历 ID
     * @param body     含 visibility 字段
     * @return 更新后的简历
     */
    @PutMapping("/{resumeId}/visibility")
    public ResponseEntity<ApiResponse<ResumeResponse>> updateVisibility(@CurrentActor ActorContext actor,
        @PathVariable UUID resumeId, @RequestBody Map<String, String> body) {
        ResumeResponse data = ResumeResponse.from(
            resumeService.updateVisibility(actor, resumeId, body.get("visibility")));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }
}
