package com.example.pwt.work.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.work.domain.ProjectStatus;
import com.example.pwt.work.infrastructure.ProjectEntity;
import com.example.pwt.work.infrastructure.ProjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 项目服务（设计文档 10.2 的 projects 接口）。
 *
 * <p>读取一律带 user_id 条件；非 Owner 主体额外按 visibility 过滤，
 * 因为匿名与 Agent 场景下多租户拦截器不追加 user_id 条件，两者不可互相替代。
 *
 * @author pwt
 */
@Service
public class ProjectService {

    private final ProjectMapper projectMapper;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param projectMapper 项目 Mapper
     * @param auditRecorder 审计记录器
     */
    public ProjectService(ProjectMapper projectMapper, AuditRecorder auditRecorder) {
        this.projectMapper = projectMapper;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 创建项目。
     *
     * @param actor       已认证主体
     * @param name        项目名称
     * @param description 项目说明
     * @param status      状态，为空时取 active
     * @param visibility  可见性，为空时取 private
     * @param startedAt   开始日期
     * @param endedAt     结束日期
     * @param metadata    扩展元数据
     * @return 新建项目
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectEntity create(ActorContext actor, String name, String description, String status,
                                String visibility, LocalDate startedAt, LocalDate endedAt,
                                Map<String, Object> metadata) {
        Assert.hasText(name, "name must not be blank");
        AccessPolicy.requireAssignable(actor, visibility);
        validatePeriod(startedAt, endedAt);

        ProjectEntity entity = new ProjectEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setName(name.trim());
        entity.setDescription(description);
        entity.setStatus(ProjectStatus.fromValueOrDefault(status, ProjectStatus.ACTIVE).getValue());
        entity.setVisibility(Visibility.fromValueOrDefault(visibility, Visibility.PRIVATE).getValue());
        entity.setStartedAt(startedAt);
        entity.setEndedAt(endedAt);
        entity.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));
        entity.setCreatedAt(Instant.now());
        projectMapper.insert(entity);

        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "project.create", "project", UUID.fromString(entity.getId())));
        return entity;
    }

    /**
     * 更新项目字段，仅覆盖传入的非空值。
     *
     * @param actor       已认证主体
     * @param projectId   项目 ID
     * @param name        项目名称，可为空表示不变
     * @param description 项目说明，可为空表示不变
     * @param status      状态，可为空表示不变
     * @param visibility  可见性，可为空表示不变
     * @param startedAt   开始日期，可为空表示不变
     * @param endedAt     结束日期，可为空表示不变
     * @param metadata    扩展元数据，可为空表示不变
     * @return 更新后的项目
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectEntity update(ActorContext actor, UUID projectId, String name, String description,
                                String status, String visibility, LocalDate startedAt, LocalDate endedAt,
                                Map<String, Object> metadata) {
        AccessPolicy.requireAssignable(actor, visibility);
        ProjectEntity entity = requireReadable(actor, projectId);
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (description != null) {
            entity.setDescription(description);
        }
        if (status != null && !status.isBlank()) {
            entity.setStatus(ProjectStatus.fromValue(status).getValue());
        }
        if (visibility != null && !visibility.isBlank()) {
            entity.setVisibility(Visibility.fromValue(visibility).getValue());
        }
        if (startedAt != null) {
            entity.setStartedAt(startedAt);
        }
        if (endedAt != null) {
            entity.setEndedAt(endedAt);
        }
        if (metadata != null) {
            entity.setMetadata(Map.copyOf(metadata));
        }
        validatePeriod(entity.getStartedAt(), entity.getEndedAt());
        projectMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "project.update", "project", projectId));
        return entity;
    }

    /**
     * 归档项目（软删除）。
     *
     * @param actor     已认证主体
     * @param projectId 项目 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(ActorContext actor, UUID projectId) {
        ProjectEntity entity = requireReadable(actor, projectId);
        entity.setStatus(ProjectStatus.ARCHIVED.getValue());
        entity.setDeletedAt(Instant.now());
        projectMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "project.archive", "project", projectId));
    }

    /**
     * 分页查询项目。
     *
     * @param actor  已认证主体
     * @param status 状态过滤，可为空
     * @param query  分页参数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<ProjectEntity> list(ActorContext actor, String status, PageQuery query) {
        LambdaQueryWrapper<ProjectEntity> wrapper = new LambdaQueryWrapper<ProjectEntity>()
            .eq(ProjectEntity::getUserId, actor.userId().toString())
            .isNull(ProjectEntity::getDeletedAt)
            .eq(status != null && !status.isBlank(), ProjectEntity::getStatus,
                status == null ? null : ProjectStatus.fromValue(status).getValue())
            .orderByDesc(ProjectEntity::getCreatedAt);
        Page<ProjectEntity> page = projectMapper.selectPage(
            new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(page.getRecords(), query.page(), query.size(), page.getTotal());
    }

    /**
     * 读取单个项目，按可见性校验权限。
     *
     * @param actor     已认证主体
     * @param projectId 项目 ID
     * @return 项目
     * @throws BizException 不存在、不属于该用户或可见性不允许
     */
    @Transactional(readOnly = true)
    public ProjectEntity requireReadable(ActorContext actor, UUID projectId) {
        ProjectEntity entity = projectMapper.selectById(projectId.toString());
        if (entity == null || entity.getDeletedAt() != null) {
            throw BizException.notFound("project");
        }
        // 先校验归属再校验可见性，跨用户一律按不存在处理，不泄露资源存在性
        actor.requireOwnership(UUID.fromString(entity.getUserId()));
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            throw BizException.notFound("project");
        }
        return entity;
    }

    private void validatePeriod(LocalDate startedAt, LocalDate endedAt) {
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "结束日期不能早于开始日期");
        }
    }

    private UUID subjectId(ActorContext actor) {
        return actor.agentClientId() == null ? actor.userId() : actor.agentClientId();
    }
}
