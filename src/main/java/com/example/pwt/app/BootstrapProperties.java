package com.example.pwt.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次启动引导配置：在没有用户时创建 Owner，避免空库无法登录。
 *
 * @param enabled     是否启用引导
 * @param username    Owner 登录名
 * @param password    Owner 初始口令，为空时跳过创建并告警
 * @param displayName Owner 展示名称
 * @author pwt
 */
@ConfigurationProperties(prefix = "pwt.bootstrap")
public record BootstrapProperties(boolean enabled, String username, String password, String displayName) {
}
