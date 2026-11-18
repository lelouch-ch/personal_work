package com.example.pwt.context.controller.dto;

import com.example.pwt.context.application.ContextPacket;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上下文包响应（设计文档 11.6）。
 *
 * <p>字段名用 snake_case 对齐 11.6 的示例：这份 JSON 会被 Agent 直接读进提示词，
 * 与设计文档口径一致能省去一层心智映射。仓库内部（请求体、其它接口）仍用 camelCase，
 * 因此这里逐个显式声明序列化名，不依赖全局命名策略——改全局策略会连带影响所有既有接口。
 *
 * @param userProfile         用户画像
 * @param applicableConstraints 关键约束，必须注入
 * @param styleRules          工作风格规则
 * @param relevantMemories    高相关记忆
 * @param skills              相关 Skill，M5 恒为空数组（M7 接入）
 * @param project             项目背景
 * @param contextMeta         构建过程元信息
 * @param contentBoundaryMarker 不可信内容边界标记（15.2），调用方不需要时省略
 * @author pwt
 */
public record ContextPacketResponse(
    @JsonProperty("user_profile")
    @JsonInclude(JsonInclude.Include.NON_NULL) Profile profile,
    @JsonProperty("applicable_constraints")
    List<Item> applicableConstraints,
    @JsonProperty("style_rules")
    List<StyleRule> styleRules,
    @JsonProperty("relevant_memories")
    List<Item> relevantMemories,
    List<Item> skills,
    @JsonProperty("project")
    @JsonInclude(JsonInclude.Include.NON_NULL) Project project,
    @JsonProperty("context_meta")
    Meta contextMeta,
    @JsonProperty("content_boundary_marker")
    @JsonInclude(JsonInclude.Include.NON_NULL) String contentBoundaryMarker) {

    /**
     * 由领域结果构造响应。
     *
     * @param packet 上下文包
     * @return 响应
     */
    public static ContextPacketResponse from(ContextPacket packet) {
        return new ContextPacketResponse(
            packet.profile() == null ? null
                : new Profile(packet.profile().headline(), packet.profile().summary()),
            packet.constraints().stream().map(Item::from).toList(),
            packet.styleRules().stream().map(StyleRule::from).toList(),
            packet.memories().stream().map(Item::from).toList(),
            packet.skills().stream().map(Item::from).toList(),
            packet.project() == null ? null
                : new Project(packet.project().id(), packet.project().name(), packet.project().description()),
            new Meta(packet.meta().totalCandidates(), packet.meta().selectedCount(),
                packet.meta().tokenBudget(), packet.meta().tokenEstimate(), packet.meta().recallPath(),
                packet.meta().truncatedSections(), packet.meta().droppedByQuota()),
            packet.contentBoundaryMarker());
    }

    /**
     * 画像。
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
     * @param description 说明
     */
    public record Project(UUID id, String name, String description) {
    }

    /**
     * 一条注入内容。
     *
     * @param id         ID
     * @param type       类型
     * @param summary    注入文本
     * @param confidence 置信度
     * @param score      加权总分
     * @param sourceRefs 来源工作记录
     * @param riskLevel  风险等级
     */
    public record Item(UUID id, String type, String summary, BigDecimal confidence, double score,
                       @JsonProperty("source_refs") List<UUID> sourceRefs,
                       @JsonProperty("risk_level") String riskLevel) {

        /**
         * 构造视图。
         *
         * @param item 领域项
         * @return 视图
         */
        public static Item from(ContextPacket.Item item) {
            return new Item(item.id(), item.type(), item.summary(), item.confidence(), item.score(),
                item.sourceRefs(), item.riskLevel());
        }
    }

    /**
     * 一条风格规则。
     *
     * @param key   规则键
     * @param value 规则值
     */
    public record StyleRule(String key, Object value) {

        /**
         * 构造视图。
         *
         * @param rule 领域规则
         * @return 视图
         */
        public static StyleRule from(ContextPacket.StyleRule rule) {
            return new StyleRule(rule.key(), rule.value());
        }
    }

    /**
     * 构建过程元信息。
     *
     * @param totalCandidates   候选总数
     * @param selectedCount     注入条数
     * @param tokenBudget       预算
     * @param tokenEstimate     估算 token
     * @param recallPath        召回路径
     * @param truncatedSections 被截断的段
     * @param droppedByQuota    因配额未注入的条数
     */
    public record Meta(@JsonProperty("total_candidates") int totalCandidates,
                       @JsonProperty("selected_count") int selectedCount,
                       @JsonProperty("token_budget") int tokenBudget,
                       @JsonProperty("token_estimate") int tokenEstimate,
                       @JsonProperty("recall_path") String recallPath,
                       @JsonProperty("truncated_sections") List<String> truncatedSections,
                       @JsonProperty("dropped_by_quota") Map<String, Integer> droppedByQuota) {
    }
}
