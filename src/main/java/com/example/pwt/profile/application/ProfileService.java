package com.example.pwt.profile.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.profile.infrastructure.UserProfileEntity;
import com.example.pwt.profile.infrastructure.UserProfileMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 个人画像服务（设计文档 10.2 的 profile 接口）。
 *
 * <p>画像按 user_id 唯一，读取时若记录不存在返回空画像而不是 404：
 * 新用户尚未填写画像属于正常状态，不应让 Agent 与网站报错。
 *
 * @author pwt
 */
@Service
public class ProfileService {

    /** 摘要最大长度，超出直接拒绝，避免整段长文塞进上下文。 */
    private static final int MAX_SUMMARY_LENGTH = 4000;

    private final UserProfileMapper userProfileMapper;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param userProfileMapper 画像 Mapper
     * @param auditRecorder     审计记录器
     */
    public ProfileService(UserProfileMapper userProfileMapper, AuditRecorder auditRecorder) {
        this.userProfileMapper = userProfileMapper;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 读取当前主体的画像。
     *
     * @param actor 已认证主体，需具备 {@code profile:read}
     * @return 画像；未填写过时返回仅含 user_id 的空画像
     * @throws BizException 主体无权读取该可见性
     */
    @Transactional(readOnly = true)
    public UserProfileEntity read(ActorContext actor) {
        actor.requireScope(Scope.PROFILE_READ);
        UserProfileEntity entity = selectByUser(actor.userId());
        if (entity == null) {
            return emptyProfile(actor.userId());
        }
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            throw BizException.notFound("profile");
        }
        return entity;
    }

    /**
     * 更新当前主体的画像，不存在时创建。
     *
     * @param actor      已认证主体
     * @param headline   一句话定位，可为空表示清空
     * @param summary    画像摘要，可为空表示清空
     * @param profileJson 结构化画像，可为空表示保持原值
     * @param visibility 可见性，为空时保持原值或默认 private
     * @return 更新后的画像
     */
    @Transactional(rollbackFor = Exception.class)
    public UserProfileEntity update(ActorContext actor, String headline, String summary,
                                    Map<String, Object> profileJson, String visibility) {
        Assert.isTrue(summary == null || summary.length() <= MAX_SUMMARY_LENGTH,
            "画像摘要长度不能超过 " + MAX_SUMMARY_LENGTH);
        AccessPolicy.requireAssignable(actor, visibility);

        UserProfileEntity entity = selectByUser(actor.userId());
        boolean created = entity == null;
        if (created) {
            entity = new UserProfileEntity();
            entity.setId(Uuids.randomUuidv7String());
            entity.setUserId(actor.userId().toString());
            entity.setVisibility(Visibility.PRIVATE.getValue());
            entity.setProfileJson(new LinkedHashMap<>());
        }
        entity.setHeadline(headline);
        entity.setSummary(summary);
        if (profileJson != null) {
            entity.setProfileJson(new LinkedHashMap<>(profileJson));
        }
        if (visibility != null && !visibility.isBlank()) {
            entity.setVisibility(Visibility.fromValue(visibility).getValue());
        }

        if (created) {
            userProfileMapper.insert(entity);
        } else {
            userProfileMapper.updateById(entity);
        }
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(),
            actor.agentClientId() == null ? actor.userId() : actor.agentClientId(),
            created ? "profile.create" : "profile.update", "user_profile",
            UUID.fromString(entity.getId())));
        return entity;
    }

    /**
     * 按用户读取画像，供公开页与内部装配复用。
     *
     * @param userId 用户 ID
     * @return 画像，不存在时为 {@code null}
     */
    @Transactional(readOnly = true)
    public UserProfileEntity readByUserId(UUID userId) {
        return selectByUser(userId);
    }

    private UserProfileEntity selectByUser(UUID userId) {
        return userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
            .eq(UserProfileEntity::getUserId, userId.toString())
            .last("LIMIT 1"));
    }

    private UserProfileEntity emptyProfile(UUID userId) {
        UserProfileEntity entity = new UserProfileEntity();
        entity.setUserId(userId.toString());
        entity.setVisibility(Visibility.PRIVATE.getValue());
        entity.setProfileJson(Map.of());
        entity.setUpdatedAt(Instant.EPOCH);
        return entity;
    }
}
