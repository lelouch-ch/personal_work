package com.example.pwt.context.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.context.application.StyleRuleService;
import com.example.pwt.context.controller.dto.StyleRuleRequest;
import com.example.pwt.context.controller.dto.StyleRuleResponse;
import com.example.pwt.identity.domain.ActorContext;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作风格规则的维护与审核接口。
 *
 * <p>这组路径是设计文档 10.2 之外的补充：M7 要求「风格规则审核」，而 10.2 只列了
 * 记忆与 Skill 的接口。审核必须有显式动作承载（批准与归档分开），
 * 不能靠创建时直接写 {@code status=approved} 绕过 Owner 的判断。
 * Agent 侧读取走 {@code pwt://profile/style}，需要 {@code profile:read}。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/style-rules")
public class StyleRuleController {

    private final StyleRuleService styleRuleService;

    /**
     * 构造控制器。
     *
     * @param styleRuleService 风格规则服务
     */
    public StyleRuleController(StyleRuleService styleRuleService) {
        this.styleRuleService = styleRuleService;
    }

    /**
     * 列出规则，含待审核。
     *
     * @param actor  当前主体，仅 Owner
     * @param status 状态过滤，可为空
     * @return 规则列表
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StyleRuleResponse>>> list(@CurrentActor ActorContext actor,
        @RequestParam(required = false) String status) {
        List<StyleRuleResponse> data = styleRuleService.list(actor, status).stream()
            .map(StyleRuleResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 新建规则，初始为待审核。
     *
     * @param actor   当前主体，仅 Owner
     * @param request 规则内容
     * @return 新建的规则
     */
    @PostMapping
    public ResponseEntity<ApiResponse<StyleRuleResponse>> create(@CurrentActor ActorContext actor,
        @Valid @RequestBody StyleRuleRequest request) {
        StyleRuleResponse data = StyleRuleResponse.from(styleRuleService.create(actor,
            request.ruleKey(), request.ruleValue(), request.confidence(), request.visibility()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 批准规则，批准后才会注入上下文。
     *
     * @param actor  当前主体，仅 Owner
     * @param ruleId 规则 ID
     * @return 批准后的规则
     */
    @PostMapping("/{ruleId}/approve")
    public ResponseEntity<ApiResponse<StyleRuleResponse>> approve(@CurrentActor ActorContext actor,
        @PathVariable UUID ruleId) {
        StyleRuleResponse data = StyleRuleResponse.from(styleRuleService.approve(actor, ruleId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 归档规则。
     *
     * @param actor  当前主体，仅 Owner
     * @param ruleId 规则 ID
     * @return 归档后的规则
     */
    @PostMapping("/{ruleId}/archive")
    public ResponseEntity<ApiResponse<StyleRuleResponse>> archive(@CurrentActor ActorContext actor,
        @PathVariable UUID ruleId) {
        StyleRuleResponse data = StyleRuleResponse.from(styleRuleService.archive(actor, ruleId));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }
}
