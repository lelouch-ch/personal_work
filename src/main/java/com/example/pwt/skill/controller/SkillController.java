package com.example.pwt.skill.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.skill.application.SkillCommand;
import com.example.pwt.skill.application.SkillService;
import com.example.pwt.skill.controller.dto.SkillRequest;
import com.example.pwt.skill.controller.dto.SkillResponse;
import com.example.pwt.skill.infrastructure.SkillEntity;
import jakarta.validation.Valid;
import java.util.List;
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
 * Skill 管理接口（设计文档 10.2）。
 *
 * <p>写入全部限 Owner（10.2 表格标注），Agent 通过 MCP 的 {@code get_skills} 与
 * {@code pwt://skills/list} 读取。发布与归档是 10.2 之外的补充动作：15.2 要求
 * 「Skill 发布前由 Owner 审核」，必须有一个显式动作承载这个审核结论，
 * 不能靠创建时直接写 {@code status=published} 绕过。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillService skillService;

    /**
     * 构造控制器。
     *
     * @param skillService Skill 服务
     */
    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 分页列出 Skill，含草稿。
     *
     * @param actor  当前主体，仅 Owner
     * @param status 状态过滤，可为空
     * @param page   页码，从 1 开始
     * @param size   每页条数，最大 100
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<SkillResponse>>> list(@CurrentActor ActorContext actor,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<SkillEntity> result = skillService.list(actor, status, query);
        List<SkillResponse> items = result.items().stream().map(SkillResponse::from).toList();
        PageResult<SkillResponse> data = PageResult.of(items, result.page(), result.size(), result.total());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * Skill 详情。
     *
     * @param actor   当前主体
     * @param skillId Skill ID
     * @return Skill
     */
    @GetMapping("/{skillId}")
    public ResponseEntity<ApiResponse<SkillResponse>> read(@CurrentActor ActorContext actor,
        @PathVariable UUID skillId) {
        SkillResponse data = SkillResponse.from(skillService.read(actor, skillId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 创建 Skill，初始为草稿。
     *
     * @param actor   当前主体，仅 Owner
     * @param request Skill 内容
     * @return 新建的草稿
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SkillResponse>> create(@CurrentActor ActorContext actor,
        @Valid @RequestBody SkillRequest request) {
        SkillResponse data = SkillResponse.from(skillService.create(actor, toCommand(request)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 更新 Skill：草稿原地改，已发布则返回新建的草稿版本。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId Skill ID
     * @param request 新内容
     * @return 被修改的行
     */
    @PutMapping("/{skillId}")
    public ResponseEntity<ApiResponse<SkillResponse>> update(@CurrentActor ActorContext actor,
        @PathVariable UUID skillId, @Valid @RequestBody SkillRequest request) {
        SkillResponse data = SkillResponse.from(skillService.update(actor, skillId, toCommand(request)));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 发布 Skill，同一 slug 的旧已发布版本自动归档。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId Skill ID
     * @return 发布后的 Skill
     */
    @PostMapping("/{skillId}/publish")
    public ResponseEntity<ApiResponse<SkillResponse>> publish(@CurrentActor ActorContext actor,
        @PathVariable UUID skillId) {
        SkillResponse data = SkillResponse.from(skillService.publish(actor, skillId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 归档 Skill，归档后不再参与检索与上下文注入。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId Skill ID
     * @return 归档后的 Skill
     */
    @PostMapping("/{skillId}/archive")
    public ResponseEntity<ApiResponse<SkillResponse>> archive(@CurrentActor ActorContext actor,
        @PathVariable UUID skillId) {
        SkillResponse data = SkillResponse.from(skillService.archive(actor, skillId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    private static SkillCommand toCommand(SkillRequest request) {
        return new SkillCommand(request.slug(), request.name(), request.description(),
            request.contentMd(), request.visibility(), request.sourceMemoryIds());
    }
}
