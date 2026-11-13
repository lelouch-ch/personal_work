package com.example.pwt.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.ActorType;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Secrets;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.IdentityProperties;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.AgentClientStatus;
import com.example.pwt.identity.infrastructure.AgentClientEntity;
import com.example.pwt.identity.infrastructure.AgentClientMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Agent 客户端与凭证服务。
 *
 * <p>凭证模型（设计文档 14.1）：随机 API Key，数据库只存 SHA-256 摘要；
 * 撤销即改状态，摘要不再命中活跃记录，因此 MCP 与 REST 请求立即失效。
 *
 * @author pwt
 */
@Service
public class AgentClientService {

    /** 单用户活跃客户端上限，避免无限制签发。 */
    private static final int MAX_ACTIVE_CLIENTS_PER_USER = 20;

    private final AgentClientMapper agentClientMapper;

    private final IdentityProperties properties;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param agentClientMapper 客户端 Mapper
     * @param properties        身份配置
     * @param auditRecorder     审计记录器
     */
    public AgentClientService(AgentClientMapper agentClientMapper, IdentityProperties properties,
                              AuditRecorder auditRecorder) {
        this.agentClientMapper = agentClientMapper;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 创建 Agent 客户端并签发凭证。
     *
     * @param userId        所属用户
     * @param clientName    客户端名称
     * @param clientType    客户端类型，为空时取 agent
     * @param scopes        授予的 scope，必须为合法取值
     * @param expiresAt     过期时间，可为空表示不过期
     * @return 创建结果，明文密钥只在此返回一次
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatedClient create(UUID userId, String clientName, String clientType, Set<String> scopes,
                                Instant expiresAt) {
        Assert.hasText(clientName, "clientName must not be blank");
        Assert.notEmpty(scopes, "scopes must not be empty");
        Set<String> unknown = new java.util.HashSet<>(scopes);
        unknown.removeAll(Scope.ALL);
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "Unknown scopes: " + unknown);
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "expiresAt must not be in the past");
        }
        Long active = agentClientMapper.selectCount(new LambdaQueryWrapper<AgentClientEntity>()
            .eq(AgentClientEntity::getUserId, userId.toString())
            .eq(AgentClientEntity::getStatus, AgentClientStatus.ACTIVE.getValue()));
        if (active != null && active >= MAX_ACTIVE_CLIENTS_PER_USER) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "Active agent client limit reached: " + MAX_ACTIVE_CLIENTS_PER_USER);
        }

        String plainToken = Secrets.generateToken();
        AgentClientEntity entity = new AgentClientEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(userId.toString());
        entity.setClientName(clientName.trim());
        entity.setClientType((clientType == null || clientType.isBlank()) ? "agent" : clientType.trim());
        entity.setSecretHash(Secrets.hash(plainToken, properties.agentTokenPepper()));
        entity.setScopes(List.copyOf(scopes));
        entity.setStatus(AgentClientStatus.ACTIVE.getValue());
        entity.setExpiresAt(expiresAt);
        agentClientMapper.insert(entity);

        auditRecorder.record(AuditEvent.of(userId, ActorType.OWNER, userId,
            "agent_client.create", "agent_client", UUID.fromString(entity.getId())));
        return new CreatedClient(UUID.fromString(entity.getId()), clientName.trim(), List.copyOf(scopes),
            expiresAt, plainToken, Secrets.displayPrefix(plainToken));
    }

    /**
     * 按明文密钥解析活跃客户端。
     *
     * @param plainToken 请求携带的密钥
     * @return 主体上下文；密钥无效、已撤销或已过期时返回 {@link Optional#empty()}
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<ActorContext> authenticate(String plainToken) {
        if (plainToken == null || !plainToken.startsWith(Secrets.TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String hash = Secrets.hash(plainToken, properties.agentTokenPepper());
        AgentClientEntity entity = agentClientMapper.selectOne(new LambdaQueryWrapper<AgentClientEntity>()
            .eq(AgentClientEntity::getSecretHash, hash)
            .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        if (!AgentClientStatus.ACTIVE.getValue().equals(entity.getStatus())) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(now.minus(properties.agentClockSkew()))) {
            return Optional.empty();
        }
        touchLastSeen(entity.getId(), now);
        return Optional.of(toContext(entity));
    }

    /**
     * 撤销客户端凭证。
     *
     * @param userId 所属用户
     * @param clientId 客户端 ID
     * @throws BizException 客户端不存在或不属于该用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(UUID userId, UUID clientId) {
        AgentClientEntity entity = requireOwned(userId, clientId);
        entity.setStatus(AgentClientStatus.REVOKED.getValue());
        agentClientMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(userId, ActorType.OWNER, userId,
            "agent_client.revoke", "agent_client", clientId));
    }

    private ActorContext toContext(AgentClientEntity entity) {
        List<String> scopes = entity.getScopes() == null ? List.of() : entity.getScopes();
        return ActorContext.agent(UUID.fromString(entity.getUserId()), UUID.fromString(entity.getId()),
            Set.copyOf(scopes));
    }

    private AgentClientEntity requireOwned(UUID userId, UUID clientId) {
        AgentClientEntity entity = agentClientMapper.selectById(clientId.toString());
        if (entity == null || !userId.toString().equals(entity.getUserId())) {
            throw BizException.notFound("agent client");
        }
        return entity;
    }

    private void touchLastSeen(String clientId, Instant now) {
        AgentClientEntity patch = new AgentClientEntity();
        patch.setId(clientId);
        patch.setLastSeenAt(now);
        agentClientMapper.updateById(patch);
    }

    /**
     * 创建结果。
     *
     * @param clientId      客户端 ID
     * @param clientName    客户端名称
     * @param scopes        授予的 scope
     * @param expiresAt     过期时间
     * @param plainToken    明文密钥，仅此一次返回
     * @param tokenPreview  密钥展示前缀
     * @author pwt
     */
    public record CreatedClient(UUID clientId, String clientName, List<String> scopes, Instant expiresAt,
                                String plainToken, String tokenPreview) {

        @Override
        public String toString() {
            return "CreatedClient{clientId=" + clientId + ", scopes=" + scopes + ", tokenPreview="
                + tokenPreview + "**}";
        }
    }
}
