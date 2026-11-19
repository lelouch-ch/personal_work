package com.example.pwt.skill.domain;

/**
 * Skill 状态，与 {@code pt_skill.status} 的 CHECK 取值一致（设计文档 8.3、15.2）。
 *
 * <p>只有 {@code published} 会被检索与注入上下文：15.2 要求「Skill 发布前由 Owner 审核」，
 * 而本系统的审核人就是 Owner 本人，{@code draft → published} 这个动作本身就是审核结论，
 * 因此不再叠加一层 candidate/approved 状态。
 *
 * @author pwt
 */
public enum SkillStatus {

    /** 草稿，可自由修改，Agent 读不到。 */
    DRAFT("draft"),
    /** 已发布，Agent 可按 scope 与可见性读取。 */
    PUBLISHED("published"),
    /** 归档，不再参与检索，但保留历史版本。 */
    ARCHIVED("archived");

    private final String value;

    SkillStatus(String value) {
        this.value = value;
    }

    /**
     * 获取数据库存储值。
     *
     * @return 存储值
     */
    public String getValue() {
        return value;
    }

    /**
     * 判断能否直接发布该状态的 Skill。
     *
     * <p>已归档不允许直接发布：归档表达的是「这套做法不再适用」，
     * 要重新启用应当显式改回草稿再发布，避免一次误点让过时流程重新进入 Agent 上下文。
     *
     * @return true 表示可以发布
     */
    public boolean canPublish() {
        return this == DRAFT;
    }

    /**
     * 判断能否就地修改该状态的 Skill 正文。
     *
     * @return true 表示可以原地更新
     */
    public boolean canEditInPlace() {
        return this == DRAFT;
    }

    /**
     * 按存储值解析状态。
     *
     * @param value 存储值
     * @return 状态枚举
     * @throws IllegalArgumentException 取值非法
     */
    public static SkillStatus fromValue(String value) {
        for (SkillStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown skill status: " + value);
    }
}
