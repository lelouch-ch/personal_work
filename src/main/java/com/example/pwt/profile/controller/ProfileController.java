package com.example.pwt.profile.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.controller.dto.ProfileResponse;
import com.example.pwt.profile.controller.dto.ProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人画像接口（设计文档 10.2）。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    /**
     * 构造控制器。
     *
     * @param profileService 画像服务
     */
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 获取当前主体可见的画像。
     *
     * @param actor 当前主体
     * @return 画像
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(@CurrentActor ActorContext actor) {
        ProfileResponse data = ProfileResponse.from(profileService.read(actor));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 更新当前用户画像。
     *
     * @param actor   当前主体
     * @param request 更新内容
     * @return 更新后的画像
     */
    @PutMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(@CurrentActor ActorContext actor,
        @Valid @RequestBody ProfileUpdateRequest request) {
        ProfileResponse data = ProfileResponse.from(profileService.update(actor, request.headline(),
            request.summary(), request.profileJson(), request.visibility()));
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }
}
