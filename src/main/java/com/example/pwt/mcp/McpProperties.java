package com.example.pwt.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 接入层参数（设计文档 9.1）。
 *
 * @author pwt
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pwt.mcp")
public class McpProperties {

    /**
     * 是否暴露 MCP 端点。
     *
     * <p>默认关闭：MCP 是 Agent 专用入口，未做 TLS 与限流之前不应随 REST 一起对全网开放；
     * 契约测试也不需要它启动。
     */
    private boolean enabled = false;

    /** MCP 端点路径。 */
    private String endpoint = "/mcp";

    /** 上报给客户端的服务名。 */
    private String serverName = "personal-work-twin";

    /** 上报给客户端的服务版本。 */
    private String serverVersion = "0.1.0";

    /**
     * 简历公开链接的绝对地址前缀，可为空。
     *
     * <p>为空时 {@code get_resume_url} 返回站内相对路径（与 REST 的 {@code publicUrl} 字段一致）。
     * Agent 拿到相对路径无法直接使用，因此对外部署时应配置成 {@code https://host} 形式。
     */
    private String publicBaseUrl = "";
}
