package com.example.pwt.identity.application;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 凭证解析：把 Bearer 后面的字符串换成 {@link ActorContext}。
 *
 * <p>以 {@code pwt_} 开头的按 Agent API Key 处理，其余按 Owner JWT 处理，
 * 两条路径互不混用（设计文档 14.2）。
 *
 * <p>从 REST 的认证拦截器里抽出来，是因为 MCP 入口也要做同一件事：前缀判定与
 * “无效即 401”的规则一旦在两处各写一份，就会出现同一个密钥在一边能用、
 * 在另一边被拒的漂移。抽取后 REST 与 MCP 共用此处的唯一实现。
 *
 * @author pwt
 */
@Service
public class CredentialResolver {

    /** Agent API Key 前缀，用于与 Owner JWT 区分。 */
    private static final String AGENT_KEY_PREFIX = "pwt_";

    private final OwnerTokenService ownerTokenService;

    private final AgentClientService agentClientService;

    /**
     * 构造解析器。
     *
     * @param ownerTokenService  Owner 令牌服务
     * @param agentClientService Agent 凭证服务
     */
    public CredentialResolver(OwnerTokenService ownerTokenService, AgentClientService agentClientService) {
        this.ownerTokenService = ownerTokenService;
        this.agentClientService = agentClientService;
    }

    /**
     * 解析凭证，失败时抛 {@link ErrorCode#UNAUTHENTICATED}。
     *
     * @param credential 去掉 {@code Bearer } 前缀后的凭证，可为空
     * @return 主体上下文
     * @throws BizException 凭证为空、类型不识别、已失效或已撤销
     */
    public ActorContext requireValid(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "Missing Authorization header");
        }
        String value = credential.trim();
        Optional<ActorContext> resolved = value.startsWith(AGENT_KEY_PREFIX)
            ? agentClientService.authenticate(value)
            : ownerTokenService.verify(value);
        return resolved.orElseThrow(
            () -> new BizException(ErrorCode.UNAUTHENTICATED, "Invalid or expired credential"));
    }
}
