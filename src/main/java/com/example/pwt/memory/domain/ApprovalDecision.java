package com.example.pwt.memory.domain;

/**
 * 审核策略的判定结果。
 *
 * @param decision 结论
 * @param reason   结论原因，会写入 {@code pt_memory_review.reason}，供审核界面展示
 *                 设计文档 16.3 要求展示“系统判断原因”
 * @author pwt
 */
public record ApprovalDecision(ReviewDecision decision, String reason) {

    /**
     * 构造自动批准结论。
     *
     * @param reason 原因
     * @return 结论
     */
    public static ApprovalDecision autoApprove(String reason) {
        return new ApprovalDecision(ReviewDecision.AUTO_APPROVE, reason);
    }

    /**
     * 构造需人工审核结论。
     *
     * @param reason 原因
     * @return 结论
     */
    public static ApprovalDecision pendingReview(String reason) {
        return new ApprovalDecision(ReviewDecision.PENDING_REVIEW, reason);
    }

    /**
     * 构造直接拒绝结论。
     *
     * @param reason 原因
     * @return 结论
     */
    public static ApprovalDecision reject(String reason) {
        return new ApprovalDecision(ReviewDecision.REJECT, reason);
    }
}
