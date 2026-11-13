package com.example.pwt.publicweb.controller;

import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.profile.application.ProfileService;
import com.example.pwt.profile.application.ResumeService;
import com.example.pwt.profile.controller.dto.ProfileResponse;
import com.example.pwt.profile.controller.dto.ResumeResponse;
import com.example.pwt.profile.infrastructure.UserProfileEntity;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名公开访问入口（设计文档 3.1 Anonymous、16.1 个人主页）。
 *
 * <p>匿名请求没有认证上下文，多租户拦截器不会追加 user_id 条件，因此这里的每个查询都必须
 * 显式限定 user_id 与 {@code visibility = 'public'}，二者缺一即造成跨用户泄漏。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/public")
public class PublicProfileController {

    private final ProfileService profileService;

    private final ResumeService resumeService;

    /**
     * 构造控制器。
     *
     * @param profileService 画像服务
     * @param resumeService  简历服务
     */
    public PublicProfileController(ProfileService profileService, ResumeService resumeService) {
        this.profileService = profileService;
        this.resumeService = resumeService;
    }

    /**
     * 读取指定用户已公开的画像。
     *
     * @param userId 用户 ID
     * @return 公开画像
     */
    @GetMapping("/{userId}/profile")
    public ResponseEntity<ApiResponse<ProfileResponse>> publicProfile(@PathVariable UUID userId) {
        UserProfileEntity entity = profileService.readByUserId(userId);
        if (entity == null || !AccessPolicy.anonymouslyReadable(entity.getVisibility())) {
            // 未公开与不存在返回同一结果，不向匿名访问者泄露该用户是否有画像
            throw BizException.notFound("public profile");
        }
        return ResponseEntity.ok(ApiResponse.ok(ProfileResponse.from(entity), RequestContext.requestId()));
    }

    /**
     * 读取指定用户已公开的当前简历，即简历固定公开链接。
     *
     * @param userId 用户 ID
     * @return 公开简历
     */
    @GetMapping("/{userId}/resume")
    public ResponseEntity<ApiResponse<ResumeResponse>> publicResume(@PathVariable UUID userId) {
        var entity = resumeService.readPublicCurrent(userId);
        if (entity == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "public resume not found");
        }
        return ResponseEntity.ok(ApiResponse.ok(ResumeResponse.from(entity), RequestContext.requestId()));
    }

    /**
     * 判断用户是否存在公开内容，供主页入口使用。
     *
     * @param userId 用户 ID
     * @return 是否存在公开内容
     */
    @GetMapping("/{userId}/exists")
    public ResponseEntity<ApiResponse<Boolean>> exists(@PathVariable UUID userId) {
        UserProfileEntity profile = profileService.readByUserId(userId);
        boolean profilePublic = profile != null && Visibility.PUBLIC == Visibility.fromValueOrDefault(
            profile.getVisibility(), Visibility.PRIVATE);
        boolean hasPublic = profilePublic || resumeService.readPublicCurrent(userId) != null;
        return ResponseEntity.ok(ApiResponse.ok(hasPublic, RequestContext.requestId()));
    }
}
