package com.example.pwt.memory.domain;

import com.example.pwt.common.api.SourceType;
import com.example.pwt.memory.MemoryPolicyProperties;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 审核策略：把设计文档 6.2 的风险分级与 6.3 的自动批准规则固化成一个纯判定组件。
 *
 * <p>规则按“从严者优先”的顺序短路求值，任何一条命中即给出结论，避免各处实现漂移：
 *
 * <ol>
 *   <li>6.3 第 7 条：L3（凭证与高危秘密）直接拒绝，不进入普通记忆。</li>
 *   <li>6.3 第 7 条：缺少来源证据不批准。</li>
 *   <li>6.3 第 6 条：与现有记忆重复或冲突必须人工处理，不自动覆盖。</li>
 *   <li>6.2 / 6.3 第 5 条：风险高于上限一律人工审核，不得仅凭置信度自动批准。</li>
 *   <li>6.3 第 4 条：决策与约束默认需 Owner 确认。</li>
 *   <li>6.3 第 1、3 条：Owner 明确创建或确认的内容可直接批准（敏感检测已在前面执行）。</li>
 *   <li>6.3 第 3 条：偏好与经验需要重复证据达标。</li>
 *   <li>6.3 第 2 条：低风险、来源明确、原文直接支持且置信度达标的事实自动批准。</li>
 * </ol>
 *
 * <p>结论只有 AUTO_APPROVE 与 PENDING_REVIEW 两种会改变记忆状态，REJECT 只用于 L3：
 * 敏感检测命中凭证时“拒绝并脱敏”，其余存疑情况都交给人工，符合 6.1 流程图。
 *
 * <p>风险级别不单独由模型决定：调用方必须先经 {@link SensitiveFinder} 计算服务端风险，
 * 与模型给出的等级取更严者再传入本策略（6.2）。
 *
 * @author pwt
 */
@Component
public class ApprovalPolicy {

    private final MemoryPolicyProperties properties;

    /**
     * 构造策略。
     *
     * @param properties 阈值配置
     */
    public ApprovalPolicy(MemoryPolicyProperties properties) {
        this.properties = properties;
    }

    /**
     * 判定候选记忆的处置方式。
     *
     * @param candidate 候选记忆事实
     * @return 结论与原因
     */
    public ApprovalDecision decide(CandidateFacts candidate) {
        if (candidate.riskLevel() == RiskLevel.L3) {
            return ApprovalDecision.reject("命中凭证或高危秘密（L3），不进入普通长期记忆");
        }
        if (candidate.evidenceCount() <= 0) {
            return ApprovalDecision.pendingReview("缺少来源证据，不予自动批准");
        }
        if (candidate.duplicateOfExisting()) {
            return ApprovalDecision.pendingReview("与既有记忆内容重复，需人工确认是否合并");
        }
        if (candidate.conflictsWithExisting()) {
            return ApprovalDecision.pendingReview("与既有记忆冲突，需人工确认是否替代旧版本");
        }
        if (!candidate.riskLevel().atMost(properties.maxAutoApproveRiskLevel())) {
            return ApprovalDecision.pendingReview(
                "风险等级 " + candidate.riskLevel() + " 高于自动批准上限 "
                    + properties.maxAutoApproveRiskLevel());
        }
        if (!properties.autoApproveEnabled()) {
            return ApprovalDecision.pendingReview("自动批准已关闭，全部进入人工审核");
        }
        if (candidate.memoryType() == MemoryType.DECISION || candidate.memoryType() == MemoryType.CONSTRAINT) {
            return ApprovalDecision.pendingReview("决策与约束类记忆默认需 Owner 确认，避免临时建议被写成长期规则");
        }
        boolean preferenceOrExperience = candidate.memoryType() == MemoryType.PREFERENCE
            || candidate.memoryType() == MemoryType.EXPERIENCE;
        if (candidate.explicitlyConfirmedByOwner()) {
            // 6.3 第 1、3 条：Owner 明确写入或确认，无需置信度与证据数量门槛
            return ApprovalDecision.autoApprove(preferenceOrExperience
                ? "Owner 明确确认的" + label(candidate.memoryType())
                : "Owner 手动创建的" + label(candidate.memoryType()));
        }
        if (preferenceOrExperience && candidate.evidenceCount() < properties.preferenceMinEvidence()) {
            return ApprovalDecision.pendingReview(
                label(candidate.memoryType) + "类记忆需要至少 " + properties.preferenceMinEvidence()
                    + " 条证据，当前 " + candidate.evidenceCount() + " 条");
        }
        if (candidate.memoryType() == MemoryType.GOAL) {
            return ApprovalDecision.pendingReview("目标类记忆需 Owner 确认是否仍然有效");
        }
        if (candidate.confidence() == null) {
            return ApprovalDecision.pendingReview("缺少置信度评估，无法自动批准");
        }
        if (candidate.confidence().doubleValue() < properties.autoApproveMinConfidence()) {
            return ApprovalDecision.pendingReview(
                "置信度 " + candidate.confidence().toPlainString() + " 低于自动批准阈值 "
                    + properties.autoApproveMinConfidence());
        }
        if (candidate.sourceType() == SourceType.AGENT && !candidate.supportedBySourceText()) {
            return ApprovalDecision.pendingReview("Agent 推断内容未被来源原文直接支持");
        }
        return ApprovalDecision.autoApprove(
            "风险 " + candidate.riskLevel() + "、置信度 " + candidate.confidence().toPlainString()
                + "、证据 " + candidate.evidenceCount() + " 条，满足自动批准条件");
    }

    /**
     * 候选记忆的事实集合，由调用方在服务端计算好后传入。
     *
     * @param memoryType                记忆类型
     * @param riskLevel                 服务端与模型取严后的风险等级
     * @param confidence                置信度，可为空表示未评估
     * @param evidenceCount             证据条数
     * @param sourceType                来源类型
     * @param supportedBySourceText     内容是否被来源原文直接支持
     * @param explicitlyConfirmedByOwner 是否由 Owner 明确创建或确认
     * @param duplicateOfExisting       是否与既有记忆规范化后重复
     * @param conflictsWithExisting     是否与既有记忆冲突
     * @author pwt
     */
    public record CandidateFacts(MemoryType memoryType, RiskLevel riskLevel, BigDecimal confidence,
                                 int evidenceCount, SourceType sourceType, boolean supportedBySourceText,
                                 boolean explicitlyConfirmedByOwner, boolean duplicateOfExisting,
                                 boolean conflictsWithExisting) {

        /**
         * 构造一个仅用于占位的最小事实集。
         *
         * @param type  记忆类型
         * @param level 风险等级
         * @return 事实集
         */
        public static CandidateFacts of(MemoryType type, RiskLevel level) {
            return new CandidateFacts(type, level, null, 0, SourceType.AGENT, false, false, false, false);
        }
    }

    private static String label(MemoryType type) {
        return switch (type) {
            case PREFERENCE -> "偏好";
            case EXPERIENCE -> "经验";
            case FACT -> "事实";
            case DECISION -> "决策";
            case CONSTRAINT -> "约束";
            case GOAL -> "目标";
        };
    }
}
