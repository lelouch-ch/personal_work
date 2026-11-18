package com.example.pwt.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pwt.common.api.SourceType;
import com.example.pwt.memory.MemoryPolicyProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 自动批准规则测试（设计文档 6.2、6.3，验收用例 4）。
 *
 * <p>逐条覆盖 6.3 的七条规则，并确认“任何 L2/L3 不得仅凭置信度自动批准”。
 *
 * @author pwt
 */
class ApprovalPolicyTest {

    private final ApprovalPolicy strict = new ApprovalPolicy(
        new MemoryPolicyProperties(true, RiskLevel.L1, 0.85d, 2));

    @Test
    void credentialShouldBeRejectedNotPending() {
        ApprovalDecision decision = strict.decide(facts(MemoryType.FACT, RiskLevel.L3, conf(0.99), 5));

        assertThat(decision.decision()).isEqualTo(ReviewDecision.REJECT);
        assertThat(decision.reason()).contains("L3");
    }

    @Test
    void highRiskShouldNeverAutoApproveOnConfidenceAlone() {
        // 6.3 第 5 条：L2 即便置信度满分也要人工审核
        ApprovalDecision decision = strict.decide(facts(MemoryType.FACT, RiskLevel.L2, conf(1.0), 5));

        assertThat(decision.decision()).isEqualTo(ReviewDecision.PENDING_REVIEW);
        assertThat(decision.reason()).contains("高于自动批准上限");
    }

    @Test
    void missingEvidenceShouldNotApprove() {
        // 6.3 第 7 条
        ApprovalDecision decision = strict.decide(facts(MemoryType.FACT, RiskLevel.L0, conf(1.0), 0));

        assertThat(decision.decision()).isEqualTo(ReviewDecision.PENDING_REVIEW);
        assertThat(decision.reason()).contains("缺少来源证据");
    }

    @Test
    void duplicateOrConflictShouldGoToHuman() {
        assertThat(strict.decide(withDuplicates(facts(MemoryType.FACT, RiskLevel.L0, conf(1.0), 3), true, false))
            .decision()).isEqualTo(ReviewDecision.PENDING_REVIEW);
        assertThat(strict.decide(withDuplicates(facts(MemoryType.FACT, RiskLevel.L0, conf(1.0), 3), false, true))
            .decision()).isEqualTo(ReviewDecision.PENDING_REVIEW);
    }

    @Test
    void decisionAndConstraintShouldRequireOwner() {
        // 6.3 第 4 条
        for (MemoryType type : List.of(MemoryType.DECISION, MemoryType.CONSTRAINT)) {
            assertThat(strict.decide(facts(type, RiskLevel.L0, conf(1.0), 3)).decision())
                .as("%s 应默认待审", type)
                .isEqualTo(ReviewDecision.PENDING_REVIEW);
        }
    }

    @Test
    void ownerDeclaredFactShouldAutoApprove() {
        // 6.3 第 1 条
        ApprovalDecision decision = strict.decide(confirmed(facts(MemoryType.FACT, RiskLevel.L0, null, 1)));

        assertThat(decision.decision()).isEqualTo(ReviewDecision.AUTO_APPROVE);
        assertThat(decision.reason()).contains("Owner 手动创建");
    }

    @Test
    void preferenceNeedsRepeatedEvidence() {
        // 6.3 第 3 条：单条证据不足
        assertThat(strict.decide(facts(MemoryType.PREFERENCE, RiskLevel.L1, conf(0.9), 1)).decision())
            .isEqualTo(ReviewDecision.PENDING_REVIEW);
        // 两条证据达标
        assertThat(strict.decide(supported(facts(MemoryType.PREFERENCE, RiskLevel.L1, conf(0.9), 2)))
            .decision()).isEqualTo(ReviewDecision.AUTO_APPROVE);
    }

    @Test
    void ownerConfirmedPreferenceSkipsEvidenceCount() {
        // 6.3 第 3 条：“重复证据数”与“用户明确确认”满足其一即可
        assertThat(strict.decide(confirmed(facts(MemoryType.PREFERENCE, RiskLevel.L1, conf(0.6), 1)))
            .decision()).isEqualTo(ReviewDecision.AUTO_APPROVE);
    }

    @Test
    void agentInferenceWithoutSourceSupportNeedsReview() {
        // 6.3 第 2 条的反面：低风险但原文不直接支持
        ApprovalDecision decision = strict.decide(facts(MemoryType.FACT, RiskLevel.L1, conf(0.95), 2));

        assertThat(decision.decision()).isEqualTo(ReviewDecision.PENDING_REVIEW);
        assertThat(decision.reason()).contains("未被来源原文直接支持");
    }

    @Test
    void lowRiskSupportedFactShouldAutoApprove() {
        assertThat(strict.decide(supported(facts(MemoryType.FACT, RiskLevel.L1, conf(0.9), 1))).decision())
            .isEqualTo(ReviewDecision.AUTO_APPROVE);
    }

    @Test
    void missingConfidenceNeedsReview() {
        assertThat(strict.decide(supported(facts(MemoryType.FACT, RiskLevel.L1, null, 1))).decision())
            .isEqualTo(ReviewDecision.PENDING_REVIEW);
    }

    @Test
    void switchOffDisablesAutoApproveButNotRejection() {
        ApprovalPolicy off = new ApprovalPolicy(new MemoryPolicyProperties(false, RiskLevel.L1, 0.85d, 2));

        assertThat(off.decide(supported(facts(MemoryType.FACT, RiskLevel.L0, conf(1.0), 1))).decision())
            .isEqualTo(ReviewDecision.PENDING_REVIEW);
        // L3 的拒绝不受开关影响：关闭自动批准不等于允许凭证入库
        assertThat(off.decide(facts(MemoryType.FACT, RiskLevel.L3, conf(1.0), 1)).decision())
            .isEqualTo(ReviewDecision.REJECT);
    }

    @Test
    void raisingRiskCeilingShouldWidenAutoApprove() {
        ApprovalPolicy loose = new ApprovalPolicy(new MemoryPolicyProperties(true, RiskLevel.L2, 0.85d, 2));

        assertThat(loose.decide(supported(facts(MemoryType.FACT, RiskLevel.L2, conf(0.9), 1))).decision())
            .isEqualTo(ReviewDecision.AUTO_APPROVE);
    }

    @Test
    void thresholdsShouldFallBackWhenOutOfRange() {
        MemoryPolicyProperties defaults = new MemoryPolicyProperties(true, null, 0d, 0);

        assertThat(defaults.maxAutoApproveRiskLevel()).isEqualTo(RiskLevel.L1);
        assertThat(defaults.autoApproveMinConfidence()).isEqualTo(0.85d);
        assertThat(defaults.preferenceMinEvidence()).isEqualTo(2);
    }

    private static BigDecimal conf(double value) {
        return BigDecimal.valueOf(value);
    }

    private static ApprovalPolicy.CandidateFacts facts(MemoryType type, RiskLevel risk,
                                                       BigDecimal confidence, int evidence) {
        return new ApprovalPolicy.CandidateFacts(type, risk, confidence, evidence, SourceType.AGENT,
            false, false, false, false);
    }

    private static ApprovalPolicy.CandidateFacts supported(ApprovalPolicy.CandidateFacts base) {
        return copy(base, true, base.explicitlyConfirmedByOwner(), false, false);
    }

    private static ApprovalPolicy.CandidateFacts confirmed(ApprovalPolicy.CandidateFacts base) {
        return new ApprovalPolicy.CandidateFacts(base.memoryType(), base.riskLevel(), base.confidence(),
            base.evidenceCount(), SourceType.MANUAL, base.supportedBySourceText(), true, false, false);
    }

    private static ApprovalPolicy.CandidateFacts withDuplicates(ApprovalPolicy.CandidateFacts base,
                                                                boolean duplicate, boolean conflict) {
        return copy(base, base.supportedBySourceText(), base.explicitlyConfirmedByOwner(), duplicate, conflict);
    }

    private static ApprovalPolicy.CandidateFacts copy(ApprovalPolicy.CandidateFacts base, boolean supported,
                                                      boolean confirmed, boolean duplicate, boolean conflict) {
        return new ApprovalPolicy.CandidateFacts(base.memoryType(), base.riskLevel(), base.confidence(),
            base.evidenceCount(), base.sourceType(), supported, confirmed, duplicate, conflict);
    }
}
