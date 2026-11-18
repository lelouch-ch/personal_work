package com.example.pwt.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 相似度测试（设计文档 11.3 第 6 步）。
 *
 * @author pwt
 */
class SimilarityTest {

    @Test
    void shouldTreatIdenticalTextAsFullySimilar() {
        assertThat(Similarity.jaccard("Feign 上传要用 RequestPart", "Feign 上传要用 RequestPart")).isEqualTo(1.0D);
        assertThat(Similarity.duplicate("同一条记忆", "同一条记忆")).isTrue();
    }

    @Test
    void shouldIgnoreCaseAndWhitespaceDifferences() {
        assertThat(Similarity.jaccard("  FEIGN   上传 ", "feign 上传")).isEqualTo(1.0D);
    }

    @Test
    void shouldNotMergeDifferentFactsOnSameTopic() {
        // 同一主题的不同事实都必须保留：误判合并会让一条记忆从上下文里静默消失
        String left = "Feign 上传接口必须使用 RequestPart 注解标注文件参数";
        String right = "Feign 上传接口的网关超时需要单独调大到 60 秒";

        assertThat(Similarity.duplicate(left, right)).isFalse();
    }

    @Test
    void shouldMergeNearlyIdenticalSentences() {
        String left = "部署脚本必须先在测试环境验证通过之后才能上生产";
        String right = "部署脚本必须先在测试环境验证通过之后才能上生产环境";

        assertThat(Similarity.duplicate(left, right)).isTrue();
    }

    @Test
    void shouldNotMergeWhenTextIsTooShortToCompare() {
        // 不足 2 字算不出 gram，返回 0 而不是“完全相似”：空正文的记忆往往是刚被脱敏掉的条目，
        // 判成相似会让它顶掉后面正常的那条，宁可都保留
        assertThat(Similarity.jaccard(null, "长文本内容")).isZero();
        assertThat(Similarity.jaccard("", "长文本内容")).isZero();
        assertThat(Similarity.jaccard("锁", "锁")).isZero();
        assertThat(Similarity.duplicate("锁", "锁")).isFalse();
    }

    @Test
    void shouldScoreUnrelatedTextLow() {
        assertThat(Similarity.jaccard("数据库连接池超时配置", "周报模板需要按项目分组")).isLessThan(0.2D);
    }
}
