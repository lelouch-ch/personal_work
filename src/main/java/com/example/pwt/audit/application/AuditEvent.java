package com.example.pwt.audit.application;

import com.example.pwt.common.api.ActorType;
import java.util.UUID;

/**
 * 审计事件（设计文档 15.3）。
 *
 * <p>只记录资源 ID 与必要元数据，不保存完整敏感正文。
 *
 * @param userId       数据所有者
 * @param actorType    主体类型
 * @param actorId      主体 ID
 * @param action       动作标识，如 {@code agent_client.create}
 * @param resourceType 资源类型
 * @param resourceId   资源 ID，可为空
 * @author pwt
 */
public record AuditEvent(UUID userId, ActorType actorType, UUID actorId, String action, String resourceType,
                         UUID resourceId) {

    /**
     * 构造审计事件。
     *
     * @param userId       数据所有者
     * @param actorType    主体类型
     * @param actorId      主体 ID
     * @param action       动作标识
     * @param resourceType 资源类型
     * @param resourceId   资源 ID
     * @return 审计事件
     */
    public static AuditEvent of(UUID userId, ActorType actorType, UUID actorId, String action,
                                String resourceType, UUID resourceId) {
        return new AuditEvent(userId, actorType, actorId, action, resourceType, resourceId);
    }
}
