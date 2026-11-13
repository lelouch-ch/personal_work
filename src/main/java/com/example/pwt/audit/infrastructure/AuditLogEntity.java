package com.example.pwt.audit.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作审计持久化对象，映射 {@code pt_audit_log}。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName("pt_audit_log")
public class AuditLogEntity {

    /** 审计记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 数据所有者。 */
    private String userId;

    /** 主体类型：owner / agent / admin / system / anonymous。 */
    private String actorType;

    /** 主体 ID。 */
    private String actorId;

    /** 动作标识。 */
    private String action;

    /** 资源类型。 */
    private String resourceType;

    /** 资源 ID。 */
    private String resourceId;

    /** 请求链路标识。 */
    private String requestId;

    /** 变更前的字段差异摘要，M1 暂不填充。 */
    private String beforeData;

    /** 变更后的字段差异摘要，M1 暂不填充。 */
    private String afterData;

    /** 来源 IP，文本存储以兼容 IPv6。 */
    private String ipAddress;

    /** User-Agent。 */
    private String userAgent;

    /** 记录时间，UTC。 */
    private Instant createdAt;
}
