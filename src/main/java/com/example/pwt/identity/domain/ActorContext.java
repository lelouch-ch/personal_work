package com.example.pwt.identity.domain;

import com.example.pwt.common.api.ActorType;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.util.Set;
import java.util.UUID;

/**
 * 已认证主体的上下文，由服务端从凭证解析得到，不接受客户端传入的 user_id。
 *
 * @param actorType     主体类型
 * @param userId        数据所有者，所有业务读写以此为隔离键
 * @param agentClientId Agent 主体对应的客户端 ID；Owner 会话为 {@code null}
 * @param scopes        Agent 被授予的 scope；Owner 会话为空集合
 * @author pwt
 */
public record ActorContext(ActorType actorType, UUID userId, UUID agentClientId, Set<String> scopes) {

    public ActorContext {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /**
     * 构造 Owner 会话上下文。
     *
     * @param userId 用户 ID
     * @return 上下文
     */
    public static ActorContext owner(UUID userId) {
        return new ActorContext(ActorType.OWNER, userId, null, Set.of());
    }

    /**
     * 构造 Agent 上下文。
     *
     * @param userId        用户 ID
     * @param agentClientId 客户端 ID
     * @param scopes        授予的 scope
     * @return 上下文
     */
    public static ActorContext agent(UUID userId, UUID agentClientId, Set<String> scopes) {
        return new ActorContext(ActorType.AGENT, userId, agentClientId, scopes);
    }

    /**
     * 判断是否为 Owner 会话。Owner 不受 scope 限制（设计文档 14.2）。
     *
     * @return true 表示 Owner
     */
    public boolean isOwner() {
        return actorType == ActorType.OWNER;
    }

    /**
     * 判断是否持有指定 scope。
     *
     * @param scope 所需 scope
     * @return true 表示已授予
     */
    public boolean hasScope(String scope) {
        return isOwner() || scopes.contains(scope);
    }

    /**
     * 校验 scope，缺失时抛出 {@link ErrorCode#SCOPE_REQUIRED}。
     *
     * @param scope 所需 scope
     * @throws BizException scope 缺失
     */
    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw BizException.scopeRequired(scope);
        }
    }

    /**
     * 校验资源归属，跨用户访问统一按“不存在”处理，避免泄露资源存在性。
     *
     * @param ownerUserId 资源所属用户
     * @throws BizException 归属不符
     */
    public void requireOwnership(UUID ownerUserId) {
        if (!userId.equals(ownerUserId)) {
            throw BizException.notFound("resource");
        }
    }
}
