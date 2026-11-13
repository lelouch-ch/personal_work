package com.example.pwt.common.api;

/**
 * 操作主体类型，与 {@code pt_audit_log.actor_type} 取值一致。
 *
 * @author pwt
 */
public enum ActorType {

    /** 数据所有者（网站会话）。 */
    OWNER,
    /** 接入 Agent（Bearer Token + scope）。 */
    AGENT,
    /** 平台管理员。 */
    ADMIN,
    /** 系统内部任务。 */
    SYSTEM,
    /** 匿名访问者。 */
    ANONYMOUS
}
