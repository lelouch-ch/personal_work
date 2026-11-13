package com.example.pwt.identity.controller;

import com.example.pwt.app.web.CurrentActor;
import com.example.pwt.common.api.ApiResponse;
import com.example.pwt.common.web.RequestContext;
import com.example.pwt.identity.application.UserAuthService;
import com.example.pwt.identity.controller.dto.LoginRequest;
import com.example.pwt.identity.controller.dto.LoginResponse;
import com.example.pwt.identity.domain.ActorContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner 认证入口。
 *
 * @author pwt
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserAuthService userAuthService;

    /**
     * 构造控制器。
     *
     * @param userAuthService 登录服务
     */
    public AuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    /**
     * 登录并签发 Owner 访问令牌。
     *
     * @param request 登录请求
     * @return 令牌与过期时间
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        UserAuthService.LoginResult result = userAuthService.login(request.username(), request.password());
        LoginResponse data = new LoginResponse(result.userId(), result.token().token(), "Bearer",
            result.token().expiresAt());
        return ResponseEntity.ok(ApiResponse.ok(data, RequestContext.requestId()));
    }

    /**
     * 回显当前认证主体，用于验证 Owner JWT 与 Agent API Key 两条鉴权路径。
     *
     * @param actor 服务端解析出的主体上下文
     * @return 主体信息
     */
    @GetMapping("/whoami")
    public ResponseEntity<ApiResponse<ActorContext>> whoami(@CurrentActor ActorContext actor) {
        return ResponseEntity.ok(ApiResponse.ok(actor, RequestContext.requestId()));
    }
}
