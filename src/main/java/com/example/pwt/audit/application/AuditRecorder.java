package com.example.pwt.audit.application;

import com.example.pwt.audit.infrastructure.AuditLogEntity;
import com.example.pwt.audit.infrastructure.AuditLogMapper;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计记录器。
 *
 * <p>审计写入使用独立事务：业务回滚不应抹掉“发生过一次操作”的事实，
 * 审计写入失败也不能拖垮主业务（设计文档 15.3）。
 *
 * @author pwt
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    /** 来源 IP 的候选请求头，按反向代理链路顺序取第一个非空值。 */
    private static final String[] IP_HEADERS = {"X-Forwarded-For", "X-Real-IP"};

    private final AuditLogMapper auditLogMapper;

    /**
     * 构造记录器。
     *
     * @param auditLogMapper 审计 Mapper
     */
    public AuditRecorder(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录一条审计事件。
     *
     * @param event 审计事件
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(AuditEvent event) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(event.userId() == null ? null : event.userId().toString());
        entity.setActorType(event.actorType().name().toLowerCase());
        entity.setActorId(event.actorId() == null ? null : event.actorId().toString());
        entity.setAction(event.action());
        entity.setResourceType(event.resourceType());
        entity.setResourceId(event.resourceId() == null ? null : event.resourceId().toString());
        entity.setRequestId(RequestContext.requestId());
        entity.setCreatedAt(Instant.now());
        fillRequestMetadata(entity);
        try {
            auditLogMapper.insert(entity);
        } catch (RuntimeException e) {
            log.warn("写入审计失败, action={}, resourceType={}", event.action(), event.resourceType(), e);
        }
    }

    private void fillRequestMetadata(AuditLogEntity entity) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        entity.setIpAddress(resolveClientIp(request));
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 500) {
            userAgent = userAgent.substring(0, 500);
        }
        entity.setUserAgent(userAgent);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        // 异步 Worker 与定时任务没有请求上下文，此时只记录业务字段
        return null;
    }

    private String resolveClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                String ip = comma > 0 ? value.substring(0, comma) : value;
                return ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
