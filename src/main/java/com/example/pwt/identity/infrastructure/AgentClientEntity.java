package com.example.pwt.identity.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 接入客户端持久化对象，映射 {@code pt_agent_client}。
 *
 * <p>{@code secretHash} 只存摘要，明文密钥仅在创建响应中出现一次（设计文档 14.1）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_agent_client", autoResultMap = true)
public class AgentClientEntity {

    /** 客户端 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户，Agent 不得跨用户访问。 */
    private String userId;

    /** 客户端名称。 */
    private String clientName;

    /** 客户端类型，默认 agent。 */
    private String clientType;

    /** 密钥摘要。 */
    private String secretHash;

    /** 授予的 scope 列表，存储为 JSON 数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> scopes;

    /** 状态：active / revoked / expired。 */
    private String status;

    /** 过期时间，NULL 表示不过期。 */
    private Instant expiresAt;

    /** 最近一次成功鉴权时间。 */
    private Instant lastSeenAt;

    /** 创建时间，UTC。 */
    private Instant createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护。 */
    private Instant updatedAt;
}
