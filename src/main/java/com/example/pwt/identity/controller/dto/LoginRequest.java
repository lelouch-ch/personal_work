package com.example.pwt.identity.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * @param username    登录名
 * @param password    明文口令，仅在 HTTPS 传输，不写日志
 * @author pwt
 */
public record LoginRequest(
    @NotBlank(message = "登录名不能为空")
    @Size(max = 100, message = "登录名长度不能超过 100")
    String username,

    @NotBlank(message = "口令不能为空")
    @Size(max = 256, message = "口令长度不能超过 256")
    String password) {

    @Override
    public String toString() {
        return "LoginRequest{username=" + username + ", password=***}";
    }
}
