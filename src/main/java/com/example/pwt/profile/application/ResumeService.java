package com.example.pwt.profile.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.profile.infrastructure.ResumeEntity;
import com.example.pwt.profile.infrastructure.ResumeMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 简历服务：版本留存、当前版本切换与公开链接（设计文档 10.2、M2）。
 *
 * <p>每个用户最多一份当前简历由 {@code pt_resume.current_user_id} 生成列上的唯一索引保证
 * （替代 PostgreSQL 部分索引）。切换当前版本必须在同一事务内先清除旧标记再置新标记，
 * 否则唯一索引会拒绝第二条当前记录。
 *
 * @author pwt
 */
@Service
public class ResumeService {

    /** 正文最大长度，与 API 层约束一致（设计文档 8.4 第 7 条）。 */
    private static final int MAX_CONTENT_LENGTH = 30000;

    private final ResumeMapper resumeMapper;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param resumeMapper  简历 Mapper
     * @param auditRecorder 审计记录器
     */
    public ResumeService(ResumeMapper resumeMapper, AuditRecorder auditRecorder) {
        this.resumeMapper = resumeMapper;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 读取当前简历。
     *
     * @param actor 已认证主体，需具备 {@code profile:read}
     * @return 当前简历
     * @throws BizException 尚无简历，或可见性不允许该主体读取
     */
    @Transactional(readOnly = true)
    public ResumeEntity readCurrent(ActorContext actor) {
        actor.requireScope(Scope.PROFILE_READ);
        ResumeEntity entity = selectCurrent(actor.userId());
        if (entity == null) {
            throw BizException.notFound("resume");
        }
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            // 对无权主体按不存在处理，避免泄露“有简历但看不到”这一事实
            throw BizException.notFound("resume");
        }
        return entity;
    }

    /**
     * 新建简历版本。
     *
     * <p>{@code makeCurrent} 为真时，先清除同用户其它版本的当前标记再写入新版本，
     * 保证生成列唯一索引不被违反。
     *
     * @param actor      已认证主体
     * @param title      标题
     * @param contentMd  Markdown 正文
     * @param resumeJson 结构化内容，可为空
     * @param visibility 可见性，可为空表示 private
     * @param makeCurrent 是否设为当前版本
     * @return 新建的简历
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity create(ActorContext actor, String title, String contentMd,
                               Map<String, Object> resumeJson, String visibility, boolean makeCurrent) {
        Assert.hasText(title, "title must not be blank");
        Assert.hasText(contentMd, "contentMd must not be blank");
        Assert.isTrue(contentMd.length() <= MAX_CONTENT_LENGTH,
            "简历正文长度不能超过 " + MAX_CONTENT_LENGTH);
        AccessPolicy.requireAssignable(actor, visibility);

        if (makeCurrent) {
            clearCurrentFlag(actor.userId());
        }
        int maxVersion = maxVersion(actor.userId());
        ResumeEntity entity = new ResumeEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setVersion(maxVersion + 1);
        entity.setTitle(title.trim());
        entity.setContentMd(contentMd);
        entity.setResumeJson(resumeJson == null ? Map.of() : Map.copyOf(resumeJson));
        entity.setVisibility(Visibility.fromValueOrDefault(visibility, Visibility.PRIVATE).getValue());
        // 首份简历自动成为当前版本，否则用户登录后看不到任何简历
        entity.setIsCurrent(makeCurrent || maxVersion == 0);
        try {
            resumeMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发创建同一版本或同时抢当前标记：交由调用方重试，不做静默覆盖
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "简历版本创建冲突，请重试", e);
        }
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(),
            actor.agentClientId() == null ? actor.userId() : actor.agentClientId(),
            "resume.create", "resume", UUID.fromString(entity.getId())));
        return entity;
    }

    /**
     * 切换当前简历版本。
     *
     * @param actor    已认证主体
     * @param resumeId 目标简历 ID
     * @return 切换后的当前简历
     * @throws BizException 简历不存在或不属于当前用户
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity makeCurrent(ActorContext actor, UUID resumeId) {
        ResumeEntity target = requireOwned(actor, resumeId);
        clearCurrentFlag(actor.userId());
        target.setIsCurrent(true);
        resumeMapper.updateById(target);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(),
            actor.agentClientId() == null ? actor.userId() : actor.agentClientId(),
            "resume.makeCurrent", "resume", resumeId));
        return target;
    }

    /**
     * 调整简历可见性，即“发布/撤回公开”动作。
     *
     * @param actor      已认证主体
     * @param resumeId   简历 ID
     * @param visibility 目标可见性
     * @return 更新后的简历
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity updateVisibility(ActorContext actor, UUID resumeId, String visibility) {
        Assert.hasText(visibility, "visibility must not be blank");
        AccessPolicy.requireAssignable(actor, visibility);
        ResumeEntity entity = requireOwned(actor, resumeId);
        entity.setVisibility(Visibility.fromValue(visibility).getValue());
        resumeMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(),
            actor.agentClientId() == null ? actor.userId() : actor.agentClientId(),
            "resume.visibility", "resume", resumeId));
        return entity;
    }

    /**
     * 列出当前用户的全部简历版本。
     *
     * @param actor 已认证主体
     * @return 按版本倒序的简历列表
     */
    @Transactional(readOnly = true)
    public List<ResumeEntity> listVersions(ActorContext actor) {
        return resumeMapper.selectList(new LambdaQueryWrapper<ResumeEntity>()
            .eq(ResumeEntity::getUserId, actor.userId().toString())
            .orderByDesc(ResumeEntity::getVersion));
    }

    /**
     * 读取指定用户已公开的当前简历，供匿名访问（设计文档 M2 验收：匿名只能访问 public）。
     *
     * <p>匿名场景没有认证上下文，多租户拦截器不会追加 user_id 条件，
     * 因此这里必须同时显式限定 user_id 与 visibility，二者缺一不可。
     *
     * @param userId 用户 ID
     * @return 公开简历，未公开或不存在时为 {@code null}
     */
    @Transactional(readOnly = true)
    public ResumeEntity readPublicCurrent(UUID userId) {
        return resumeMapper.selectOne(new LambdaQueryWrapper<ResumeEntity>()
            .eq(ResumeEntity::getUserId, userId.toString())
            .eq(ResumeEntity::getIsCurrent, true)
            .eq(ResumeEntity::getVisibility, Visibility.PUBLIC.getValue())
            .last("LIMIT 1"));
    }

    private ResumeEntity selectCurrent(UUID userId) {
        return resumeMapper.selectOne(new LambdaQueryWrapper<ResumeEntity>()
            .eq(ResumeEntity::getUserId, userId.toString())
            .eq(ResumeEntity::getIsCurrent, true)
            .last("LIMIT 1"));
    }

    private void clearCurrentFlag(UUID userId) {
        resumeMapper.update(null, new LambdaUpdateWrapper<ResumeEntity>()
            .eq(ResumeEntity::getUserId, userId.toString())
            .eq(ResumeEntity::getIsCurrent, true)
            .set(ResumeEntity::getIsCurrent, false));
    }

    private int maxVersion(UUID userId) {
        List<ResumeEntity> versions = resumeMapper.selectList(new LambdaQueryWrapper<ResumeEntity>()
            .eq(ResumeEntity::getUserId, userId.toString())
            .select(ResumeEntity::getVersion));
        return versions.stream().mapToInt(ResumeEntity::getVersion).max().orElse(0);
    }

    private ResumeEntity requireOwned(ActorContext actor, UUID resumeId) {
        ResumeEntity entity = resumeMapper.selectById(resumeId.toString());
        if (entity == null || !actor.userId().toString().equals(entity.getUserId())) {
            throw BizException.notFound("resume");
        }
        return entity;
    }
}
