package com.example.pwt.context.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pwt.context.ContextProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 上下文组装测试：排序、去相似与预算分配（设计文档 11.3 第 5～7 步、11.4、11.5）。
 *
 * <p>这是 M5 最需要断言的部分：“预算不足时先丢谁”决定了 Agent 拿到的是不是可用上下文，
 * 而它完全发生在内存里，与数据库无关，因此用给定输入直接验证。
 *
 * @author pwt
 */
class ContextComposerTest {

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

    private final ContextProperties properties = new ContextProperties();

    @Test
    void shouldRankByWeightedScoreNotByRecallOrder() {
        // 两条全文检索分数相同的候选，靠重要度与置信度分高下
        ContextCandidate strong = candidate("experience", "经验甲：上传要区分注解", 5, "0.95", 0.9D);
        ContextCandidate weak = candidate("experience", "经验乙：随手记的一条", 1, "0.40", 0.9D);

        ContextComposer.Result result = ContextComposer.compose(List.of(weak, strong), true, null, NOW,
            4000, properties);

        assertThat(result.items()).extracting(ContextPacket.Item::summary)
            .containsExactly("经验甲：上传要区分注解", "经验乙：随手记的一条");
        assertThat(result.items().get(0).score()).isGreaterThan(result.items().get(1).score());
    }

    @Test
    void shouldNormalizeRelevanceByCandidateMax() {
        ContextCandidate top = candidate("fact", "事实甲", 3, "0.9", 1.0D);
        ContextCandidate half = candidate("fact", "事实乙", 3, "0.9", 0.5D);

        List<ContextCandidate> reversed = List.of(half, top);
        ContextComposer.Result result = ContextComposer.compose(reversed, true, null, NOW, 4000, properties);

        assertThat(result.items().get(0).summary()).isEqualTo("事实甲");
    }

    @Test
    void shouldScoreLikeFallbackAsLowRelevance() {
        // 兜底路径下所有候选的相关度分项相同，排序必须由重要度/新近度决定
        ContextCandidate important = candidate("fact", "重要事实", 5, "0.9", null);
        ContextCandidate minor = candidate("fact", "次要事实", 1, "0.9", null);

        ContextComposer.Result result = ContextComposer.compose(List.of(minor, important), false, null, NOW,
            4000, properties);

        assertThat(result.items().get(0).summary()).isEqualTo("重要事实");
    }

    @Test
    void shouldDropNearDuplicateKeepingHigherScore() {
        ContextCandidate keep = candidate("experience", "部署脚本必须先测试环境验证之后才能上生产", 5, "0.95", 0.8D);
        ContextCandidate drop = candidate("experience", "部署脚本必须先测试环境验证之后才能上生产环境", 2, "0.60", 0.8D);

        ContextComposer.Result result = ContextComposer.compose(List.of(keep, drop), true, null, NOW, 4000,
            properties);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(keep.id());
        assertThat(result.deduplicated()).isEqualTo(1);
    }

    @Test
    void shouldKeepDifferentFactsOnSameTopic() {
        ContextCandidate first = candidate("fact", "Feign 上传接口必须使用 RequestPart 标注文件参数", 4, "0.9", 0.8D);
        ContextCandidate second = candidate("fact", "Feign 上传接口的网关超时需要单独调大到 60 秒", 4, "0.9", 0.8D);

        ContextComposer.Result result = ContextComposer.compose(List.of(first, second), true, null, NOW, 4000,
            properties);

        assertThat(result.items()).hasSize(2);
    }

    @Test
    void shouldRespectPerTypeQuota() {
        // 默认 experience 配额 4：造 6 条互不相似的经验，多出的 2 条必须记入 droppedByQuota
        List<ContextCandidate> experiences = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            experiences.add(candidate("experience", "经验条目" + i + "号内容互不相同", 4, "0.9", 0.9D - i * 0.01D));
        }

        ContextComposer.Result result = ContextComposer.compose(experiences, true, null, NOW, 4000, properties);

        assertThat(result.items()).hasSize(4);
        assertThat(result.droppedByQuota()).containsEntry("experience", 2);
    }

    @Test
    void shouldNotLetOneTypeCrowdOutOthers() {
        // 经验条数多且句子长：没有配额时它们会按分数吃满预算，把偏好一条都挤不掉
        List<ContextCandidate> mixed = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mixed.add(candidate("experience", "较长的一条经验记录内容" + i, 4, "0.9", 0.9D));
        }
        mixed.add(candidate("preference", "先给结论再给步骤", 4, "0.9", 0.3D));

        ContextComposer.Result result = ContextComposer.compose(mixed, true, null, NOW, 4000, properties);

        assertThat(result.items()).anySatisfy(item -> assertThat(item.type()).isEqualTo("preference"));
    }

    @Test
    void shouldStopAndReportTruncationWhenBudgetRunsOut() {
        List<ContextCandidate> many = new java.util.ArrayList<>();
        // 十条互不相似的长正文：只差一个字符的正文会被相似度去重合并，那样预算根本不够紧
        many.add(candidate("experience", "网关超时需要在预发环境单独验证后再改配置", 4, "0.9", 0.90D));
        many.add(candidate("experience", "会议纪要的说话人分离准确率依赖音频采样率", 4, "0.9", 0.88D));
        many.add(candidate("experience", "数据库连接池耗尽通常由未关闭的游标引起", 4, "0.9", 0.86D));
        many.add(candidate("experience", "镜像体积过大源于把构建缓存一并打进最终层", 4, "0.9", 0.84D));
        many.add(candidate("experience", "接口幂等性依赖客户端传入的去重键", 4, "0.9", 0.82D));
        many.add(candidate("experience", "周报统计口径应当按自然周而非滚动七天", 4, "0.9", 0.80D));
        many.add(candidate("experience", "全文检索单字查询必须保留子串匹配兜底", 4, "0.9", 0.78D));
        many.add(candidate("experience", "上下文注入要控制预算以免撑爆模型窗口", 4, "0.9", 0.76D));
        many.add(candidate("experience", "记忆正文命中凭证类敏感信息时要整体脱敏", 4, "0.9", 0.74D));
        many.add(candidate("experience", "审核通过的旧版本在替代后要标记为已失效", 4, "0.9", 0.72D));

        ContextComposer.Result result = ContextComposer.compose(many, true, null, NOW, 120, properties);

        assertThat(result.items()).isNotEmpty();
        assertThat(result.items()).hasSizeLessThan(10);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void shouldNotBlankWholeSectionWhenTopCandidateIsOversized() {
        // 回归：最高分的那条最长（未写摘要时注入正文），一旦它超预算就整段清空，
        // Agent 会在明明还有预算可用时空手而归（端到端实测到 sel=0 / est=117 / 预算 500）
        ContextCandidate oversized = candidate("fact", "超长正文".repeat(400), 5, "0.99", 0.99D);
        ContextCandidate small = candidate("fact", "一条放得下的短事实", 4, "0.9", 0.7D);

        ContextComposer.Result result = ContextComposer.compose(List.of(oversized, small), true, null, NOW,
            500, properties);

        assertThat(result.items()).extracting(ContextPacket.Item::summary).containsExactly("一条放得下的短事实");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void shouldSelectNothingWhenBudgetIsZero() {
        ContextComposer.Result result = ContextComposer.compose(
            List.of(candidate("fact", "一条事实", 5, "0.9", 1.0D)), true, null, NOW, 0, properties);

        assertThat(result.items()).isEmpty();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void shouldBoostCandidateOfRequestedProject() {
        UUID projectId = UUID.randomUUID();
        ContextCandidate other = candidate("fact", "无项目归属的通用事实内容", 4, "0.9", 0.9D);
        ContextCandidate matched = withProject(candidate("fact", "本项目的关键背景事实", 4, "0.9", 0.9D), projectId);

        ContextComposer.Result result = ContextComposer.compose(List.of(other, matched), true, projectId, NOW,
            4000, properties);

        assertThat(result.items().get(0).id()).isEqualTo(matched.id());
    }

    @Test
    void shouldCarrySourceRefsIntoInjectedItem() {
        UUID source = UUID.randomUUID();
        ContextCandidate withSource = new ContextCandidate(UUID.randomUUID(), "experience", "带来源的经验正文",
            4, new BigDecimal("0.9000"), NOW, null, null, 0.9D, "L1", List.of(source, source, source, source,
            source, source, source));

        ContextComposer.Result result = ContextComposer.compose(List.of(withSource), true, null, NOW, 4000,
            properties);

        // 一条记忆的证据可能几十条，只保留前 5 条：全列出来挤占预算却没有额外信息量
        assertThat(result.items().get(0).sourceRefs()).hasSize(5);
    }

    private ContextCandidate candidate(String type, String text, int importance, String confidence,
                                       Double relevance) {
        return new ContextCandidate(UUID.randomUUID(), type, text, importance,
            confidence == null ? null : new BigDecimal(confidence), NOW.minus(1, ChronoUnit.DAYS), null, null,
            relevance, "L1", List.of());
    }

    private ContextCandidate withProject(ContextCandidate source, UUID projectId) {
        return new ContextCandidate(source.id(), source.type(), source.text(), source.importance(),
            source.confidence(), source.createdAt(), source.lastUsedAt(), projectId, source.relevance(),
            source.riskLevel(), source.sourceRefs());
    }
}
