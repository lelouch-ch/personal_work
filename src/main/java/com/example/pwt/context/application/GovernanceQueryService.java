package com.example.pwt.context.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.context.infrastructure.ConstraintEntity;
import com.example.pwt.context.infrastructure.ConstraintMapper;
import com.example.pwt.context.infrastructure.StyleRuleEntity;
import com.example.pwt.context.infrastructure.StyleRuleMapper;
import com.example.pwt.identity.domain.ActorContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 治理项查询：当前生效的约束与已批准的工作风格规则（设计文档 8.3、9.2、11.5）。
 *
 * <p>这两张表此前只在 {@link ContextDataReader} 内部被查，是私有方法。MCP 的
 * {@code pwt://profile/style} 与 {@code pwt://profile/constraints} 两个 Resources
 * 也要读同样的内容：如果 MCP 直接去调 infrastructure 的 Mapper，就绕过领域边界，
 * 两处还会各写一份可见性判定。因此把查询提到 application 层，由本服务作为唯一入口。
 *
 * <p>可见性判定与 {@link com.example.pwt.identity.domain.AccessPolicy} 的三档阶梯一致，
 * 但在这里以条件构造器的形式下推到 SQL：这两张表数据量小、无 JSON 列，
 * 没有需要在 Java 层复用的复杂规则。
 *
 * @author pwt
 */
@Service
public class GovernanceQueryService {

    /** 约束注入条数上限：约束是硬规则，但条数失控会吃光预算，挤掉任务真正需要的记忆。 */
    static final int MAX_CONSTRAINTS = 12;

    /** 风格规则注入条数上限。 */
    static final int MAX_STYLE_RULES = 10;

    /** 状态：生效。 */
    private static final String STATUS_ACTIVE = "active";

    /** 状态：已批准。 */
    private static final String STATUS_APPROVED = "approved";

    private final ConstraintMapper constraintMapper;

    private final StyleRuleMapper styleRuleMapper;

    /**
     * 构造服务。
     *
     * @param constraintMapper 约束 Mapper
     * @param styleRuleMapper  风格规则 Mapper
     */
    public GovernanceQueryService(ConstraintMapper constraintMapper, StyleRuleMapper styleRuleMapper) {
        this.constraintMapper = constraintMapper;
        this.styleRuleMapper = styleRuleMapper;
    }

    /**
     * 当前生效的约束，按严重程度倒序。
     *
     * @param actor 当前主体
     * @return 约束列表
     */
    @Transactional(readOnly = true)
    public List<ConstraintEntity> activeConstraints(ActorContext actor) {
        LambdaQueryWrapper<ConstraintEntity> wrapper = visible(new LambdaQueryWrapper<ConstraintEntity>()
            .eq(ConstraintEntity::getUserId, actor.userId().toString())
            .eq(ConstraintEntity::getStatus, STATUS_ACTIVE), actor, ConstraintEntity::getVisibility);
        // FIELD() 显式给定严重程度顺序：字典序会把 critical 排在 high 之后，
        // 也会让 normal 排在 low 之前，预算不足时先丢错的这一头
        wrapper.last("ORDER BY FIELD(severity, 'critical', 'high', 'normal', 'low'), created_at DESC"
            + " LIMIT " + MAX_CONSTRAINTS);
        return constraintMapper.selectList(wrapper);
    }

    /**
     * 已批准的风格规则，按置信度倒序。
     *
     * @param actor 当前主体
     * @return 规则列表
     */
    @Transactional(readOnly = true)
    public List<StyleRuleEntity> approvedStyleRules(ActorContext actor) {
        LambdaQueryWrapper<StyleRuleEntity> wrapper = visible(new LambdaQueryWrapper<StyleRuleEntity>()
            .eq(StyleRuleEntity::getUserId, actor.userId().toString())
            .eq(StyleRuleEntity::getStatus, STATUS_APPROVED), actor, StyleRuleEntity::getVisibility);
        wrapper.last("ORDER BY confidence DESC, updated_at DESC LIMIT " + MAX_STYLE_RULES);
        return styleRuleMapper.selectList(wrapper);
    }

    private <T> LambdaQueryWrapper<T> visible(LambdaQueryWrapper<T> wrapper, ActorContext actor,
                                              SFunction<T, ?> visibilityColumn) {
        if (actor.isOwner() || actor.isSystem()) {
            return wrapper;
        }
        if (actor.agentClientId() != null) {
            wrapper.in(visibilityColumn, List.of(Visibility.AGENT.getValue(), Visibility.PUBLIC.getValue()));
        } else {
            wrapper.eq(visibilityColumn, Visibility.PUBLIC.getValue());
        }
        return wrapper;
    }
}
