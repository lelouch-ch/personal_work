package com.example.pwt.context.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上下文包（设计文档 11.6）。
 *
 * <p>字段顺序按注入优先级排列：约束 → 画像 → 风格 → 相关记忆 → Skill，
 * 与 11.5 的预算分配顺序一致，调用方按顺序拼接即可，不需要自己再排一次。
 *
 * @param profile              用户画像摘要，无权读取或未设置时为空
 * @param constraints          关键约束，必须注入，不因普通相关性低而丢弃
 * @param styleRules           工作风格规则
 * @param memories             高相关事实、经验与决策
 * @param skills               相关 Skill，M5 恒为空（M7 接入）
 * @param project              请求项目的名称与说明，未指定项目或无权读取时为空
 * @param meta                 构建过程元信息，用于解释结果
 * @param contentBoundaryMarker 不可信内容边界标记（15.2）
 */
public record ContextPacket(Profile profile, List<Item> constraints, List<StyleRule> styleRules,
                            List<Item> memories, List<Item> skills, Project project, Meta meta,
                            String contentBoundaryMarker) {

    /**
     * 画像摘要。
     *
     * @param headline 一句话定位
     * @param summary  自述摘要
     */
    public record Profile(String headline, String summary) {
    }

    /**
     * 项目背景。
     *
     * @param id          项目 ID
     * @param name        项目名
     * @param description 项目说明
     */
    public record Project(UUID id, String name, String description) {
    }

    /**
     * 一条被注入的内容。
     *
     * @param id          记忆或约束 ID
     * @param type        记忆类型；约束固定为 constraint
     * @param summary     注入文本，优先用摘要，缺摘要时截断正文
     * @param confidence  置信度，约束类为空
     * @param score       加权总分，0~1
     * @param sourceRefs  来源工作记录 ID，供调用方回溯证据
     * @param riskLevel   风险等级，注入端据此二次判断
     */
    public record Item(UUID id, String type, String summary, BigDecimal confidence, double score,
                       List<UUID> sourceRefs, String riskLevel) {
    }

    /**
     * 一条风格规则。
     *
     * @param key   规则键
     * @param value 规则值
     */
    public record StyleRule(String key, Object value) {
    }

    /**
     * 构建过程元信息（设计文档 11.6 {@code context_meta}）。
     *
     * <p>{@code recallPath}、{@code truncatedSections}、{@code droppedByQuota} 三处不是为了好看：
     * 上下文包是“有预算的截断结果”，调用方拿到空结果或短结果时必须能分辨
     * “用户确实没有相关记忆”与“召回降级了 / 被预算截断了”，否则会误判为个人库里没信息，
     * 进而向用户给出没有依据的结论。
     *
     * @param totalCandidates 召回候选总数，去重之前
     * @param selectedCount   实际注入的<b>相关记忆</b>条数；约束、风格规则与 Skill 不计入，
     *                        它们的条数直接看各自的数组，口径与 M5 起保持一致
     * @param tokenBudget     本次预算
     * @param tokenEstimate   估算 Token 数
     * @param recallPath      召回路径：{@code full_text} 或 {@code like}
     * @param truncatedSections 被截断的段，取值见本类的 SECTION 常量
     * @param droppedByQuota  因类型配额未注入的条数，按记忆类型给出
     */
    public record Meta(int totalCandidates, int selectedCount, int tokenBudget, int tokenEstimate,
                       String recallPath, List<String> truncatedSections,
                       Map<String, Integer> droppedByQuota) {

        /** 全文检索路径标识。 */
        public static final String PATH_FULL_TEXT = "full_text";

        /** LIKE 兜底路径标识。 */
        public static final String PATH_LIKE = "like";

        /** 段落标识：约束。 */
        public static final String SECTION_CONSTRAINTS = "constraints";

        /** 段落标识：相关记忆。 */
        public static final String SECTION_MEMORIES = "memories";

        /** 段落标识：风格规则。 */
        public static final String SECTION_STYLE_RULES = "style_rules";

        /** 段落标识：项目背景。 */
        public static final String SECTION_PROJECT = "project";

        /** 段落标识：Skill。 */
        public static final String SECTION_SKILLS = "skills";
    }
}
