package com.example.pwt.context.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.context.infrastructure.StyleRuleEntity;
import com.example.pwt.context.infrastructure.StyleRuleMapper;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作风格规则的维护与审核（设计文档 7 章 skill 模块的「工作风格」部分、M7 验收）。
 *
 * <p>类放在 {@code context} 模块而不新建 style 包：设计文档 7 的模块表里没有独立 style 模块，
 * 而本表的唯一读取方就是 Context Builder，实体与 Mapper 也都在本模块。新建包会让
 * 同一张表的代码分裂在两个模块里，收益不抵成本。
 *
 * <p>状态机只有三档（与 {@code pt_style_rule.status} 的 CHECK 一致）：
 * {@code candidate → approved | archived}。批准由 Owner 判定——{@code sourceCount} 与
 * {@code confidence} 只作为判据展示，服务端不强设阈值：设计文档 6.3 的证据条数规则
 * 只针对记忆，风格规则目前没有任何自动产出方（M4 的提取只产六类记忆），
 * 全部规则都由 Owner 手写，再拿阈值去卡自己写的规则没有意义。
 *
 * @author pwt
 */
@Service
public class StyleRuleService {

    /** 状态：待审核。 */
    public static final String STATUS_CANDIDATE = "candidate";

    /** 状态：已批准，可注入上下文。 */
    public static final String STATUS_APPROVED = "approved";

    /** 状态：归档，不再参与上下文。 */
    public static final String STATUS_ARCHIVED = "archived";

    /** 规则键长度上限，与列宽一致。 */
    private static final int MAX_KEY_LENGTH = 120;

    private final StyleRuleMapper styleRuleMapper;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param styleRuleMapper 风格规则 Mapper
     * @param auditRecorder   审计记录器
     */
    public StyleRuleService(StyleRuleMapper styleRuleMapper, AuditRecorder auditRecorder) {
        this.styleRuleMapper = styleRuleMapper;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 列出规则，仅 Owner。
     *
     * @param actor  当前主体
     * @param status 状态过滤，可为空表示全部
     * @return 规则列表，按更新时间倒序
     */
    @Transactional(readOnly = true)
    public List<StyleRuleEntity> list(ActorContext actor, String status) {
        requireOwner(actor, "查看风格规则");
        LambdaQueryWrapper<StyleRuleEntity> wrapper = new LambdaQueryWrapper<StyleRuleEntity>()
            .eq(StyleRuleEntity::getUserId, actor.userId().toString())
            .eq(status != null && !status.isBlank(), StyleRuleEntity::getStatus, status)
            .orderByDesc(StyleRuleEntity::getUpdatedAt);
        return styleRuleMapper.selectList(wrapper);
    }

    /**
     * 新建规则，初始为待审核。
     *
     * <p>每用户内 {@code rule_key} 唯一（{@code uq_style_rule_user_key}）：要改已有规则走审核接口
     * 或直接改值，不允许再插一条同键规则，否则 Context Builder 不知道该听哪一条。
     *
     * @param actor      当前主体，仅 Owner
     * @param ruleKey    规则键
     * @param ruleValue  规则值，JSON 结构由键自行约定
     * @param confidence 置信度，可为空
     * @param visibility 可见性，可为空表示 private
     * @return 落库后的规则
     */
    @Transactional(rollbackFor = Exception.class)
    public StyleRuleEntity create(ActorContext actor, String ruleKey, Object ruleValue,
                                  BigDecimal confidence, String visibility) {
        requireOwner(actor, "创建风格规则");
        if (ruleKey == null || ruleKey.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "ruleKey 不能为空");
        }
        if (ruleKey.length() > MAX_KEY_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "ruleKey 长度不能超过 " + MAX_KEY_LENGTH);
        }
        if (ruleValue == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "ruleValue 不能为空");
        }
        requireConfidence(confidence);
        AccessPolicy.requireAssignable(actor, visibility);

        StyleRuleEntity entity = new StyleRuleEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setRuleKey(ruleKey.trim());
        entity.setRuleValue(ruleValue);
        entity.setConfidence(confidence);
        entity.setStatus(STATUS_CANDIDATE);
        entity.setSourceCount(1);
        entity.setVisibility(Visibility.fromValueOrDefault(visibility, Visibility.PRIVATE).getValue());
        // 时间显式赋值：不写则响应里是 null 而库里有值，两侧对不上
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            styleRuleMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "规则键已存在: " + entity.getRuleKey(), e);
        }
        audit(actor, "style_rule.create", entity.getId());
        return entity;
    }

    /**
     * 批准规则，批准后才会被 Context Builder 注入。
     *
     * @param actor   当前主体，仅 Owner
     * @param ruleId  规则 ID
     * @return 批准后的规则
     */
    @Transactional(rollbackFor = Exception.class)
    public StyleRuleEntity approve(ActorContext actor, UUID ruleId) {
        requireOwner(actor, "批准风格规则");
        StyleRuleEntity entity = requireOwn(actor, ruleId);
        if (STATUS_APPROVED.equals(entity.getStatus())) {
            return entity;
        }
        if (STATUS_ARCHIVED.equals(entity.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "已归档的规则不能直接批准");
        }
        entity.setStatus(STATUS_APPROVED);
        entity.setUpdatedAt(Instant.now());
        styleRuleMapper.updateById(entity);
        audit(actor, "style_rule.approve", entity.getId());
        return entity;
    }

    /**
     * 归档规则。
     *
     * @param actor  当前主体，仅 Owner
     * @param ruleId 规则 ID
     * @return 归档后的规则
     */
    @Transactional(rollbackFor = Exception.class)
    public StyleRuleEntity archive(ActorContext actor, UUID ruleId) {
        requireOwner(actor, "归档风格规则");
        StyleRuleEntity entity = requireOwn(actor, ruleId);
        if (STATUS_ARCHIVED.equals(entity.getStatus())) {
            return entity;
        }
        entity.setStatus(STATUS_ARCHIVED);
        entity.setUpdatedAt(Instant.now());
        styleRuleMapper.updateById(entity);
        audit(actor, "style_rule.archive", entity.getId());
        return entity;
    }

    private StyleRuleEntity requireOwn(ActorContext actor, UUID ruleId) {
        StyleRuleEntity entity = styleRuleMapper.selectById(ruleId.toString());
        if (entity == null || !entity.getUserId().equals(actor.userId().toString())) {
            throw BizException.notFound("style rule");
        }
        return entity;
    }

    private static void requireConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "confidence 需在 0 到 1 之间");
        }
    }

    private void requireOwner(ActorContext actor, String action) {
        if (!actor.isOwner()) {
            // 风格规则控制 Agent 的回答方式，只能由 Owner 定；Agent 侧经 profile:read 只读
            throw new BizException(ErrorCode.FORBIDDEN, "只有 Owner 可以" + action);
        }
    }

    private void audit(ActorContext actor, String action, String ruleId) {
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            action, "style_rule", UUID.fromString(ruleId)));
    }
}
