package com.example.pwt.memory;

import com.example.pwt.memory.domain.RiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆自动批准阈值（设计文档 6.3「阈值建议作为配置项，而不是硬编码」）。
 *
 * @param autoApproveEnabled        是否允许系统自动批准，关闭后全部进人工审核
 * @param maxAutoApproveRiskLevel   可自动批准的最高风险等级
 * @param autoApproveMinConfidence  自动批准要求的最低置信度，0 到 1
 * @param preferenceMinEvidence     偏好/经验自动批准所需的最低证据条数
 * @author pwt
 */
@ConfigurationProperties(prefix = "pwt.memory")
public record MemoryPolicyProperties(
    boolean autoApproveEnabled,
    RiskLevel maxAutoApproveRiskLevel,
    double autoApproveMinConfidence,
    int preferenceMinEvidence) {

    /**
     * 补全未显式配置的默认值。
     */
    public MemoryPolicyProperties {
        if (maxAutoApproveRiskLevel == null) {
            maxAutoApproveRiskLevel = RiskLevel.L1;
        }
        if (autoApproveMinConfidence <= 0 || autoApproveMinConfidence > 1) {
            autoApproveMinConfidence = 0.85d;
        }
        if (preferenceMinEvidence <= 0) {
            preferenceMinEvidence = 2;
        }
    }
}
