package com.example.pwt.context.controller.dto;

import com.example.pwt.context.infrastructure.StyleRuleEntity;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 风格规则响应。
 *
 * <p>字段名用 camelCase 与其余接口一致；注入给 Agent 的形式（{@code key: value}）
 * 由 Context Builder 负责，不在此处提前拼接。
 *
 * @param id          规则 ID
 * @param ruleKey     规则键
 * @param ruleValue   规则值
 * @param confidence  置信度
 * @param status      状态：candidate / approved / archived
 * @param sourceCount 观测来源数
 * @param visibility  可见性
 * @author pwt
 */
public record StyleRuleResponse(UUID id, String ruleKey, Object ruleValue, BigDecimal confidence,
                                String status, Integer sourceCount, String visibility) {

    /**
     * 由持久化对象构造响应。
     *
     * @param entity 规则实体
     * @return 响应
     */
    public static StyleRuleResponse from(StyleRuleEntity entity) {
        return new StyleRuleResponse(UUID.fromString(entity.getId()), entity.getRuleKey(),
            entity.getRuleValue(), entity.getConfidence(), entity.getStatus(),
            entity.getSourceCount(), entity.getVisibility());
    }
}
