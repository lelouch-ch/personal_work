package com.example.pwt.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 检索串组装测试（设计文档 11.3 第 3 步、8.4 第 15 条、8.5 单字退化）。
 *
 * @author pwt
 */
class ContextQueryTest {

    @Test
    void shouldStripBooleanOperatorsBeforeAssembling() {
        ContextQuery query = ContextQuery.parse("修复 +(上传) 失败 >的问题 <啊> ~呀 @#$ 处理");

        // 运算符必须全部消失：残留一个 '+' 就会把整条检索串变成强制包含语义
        assertThat(query.booleanQuery().codePoints()).allMatch(cp -> "+-()<>::~@#$&|!".indexOf(cp) < 0);
        assertThat(query.usableForFullText()).isTrue();
    }

    @Test
    void shouldSplitChineseIntoBigrams() {
        ContextQuery query = ContextQuery.parse("数据库连接池");

        // ngram_token_size = 2：整句入查询等于要求原文出现完全连续的同一段文字，
        // 换个说法召回就是 0，因此必须展开成 2-gram
        assertThat(query.booleanQuery().split(" ")).contains("数据", "据库", "库连", "连接", "接池");
    }

    @Test
    void shouldKeepAsciiWordsAsWhole() {
        ContextQuery query = ContextQuery.parse("Feign multipart 上传配置");

        assertThat(query.booleanQuery()).contains("feign", "multipart");
    }

    @Test
    void shouldSplitMixedAsciiAndChineseWithinOneToken() {
        ContextQuery query = ContextQuery.parse("使用Feign上传文件");

        assertThat(query.booleanQuery()).contains("feign").contains("上传").contains("传文");
    }

    @Test
    void shouldFallBackToSingleCharLikeTermWhenQueryTooShort() {
        ContextQuery query = ContextQuery.parse("锁");

        // 单字无法命中 ngram 索引：布尔串为空，但兜底词必须保留，否则静默返回空结果
        assertThat(query.usableForFullText()).isFalse();
        assertThat(query.likeTerms()).containsExactly("锁");
    }

    @Test
    void shouldPreferLongerTermsForLikeFallback() {
        ContextQuery query = ContextQuery.parse("连接池 超时 数据库死锁");

        assertThat(query.likeTerms()).startsWith("数据库死锁");
        assertThat(query.likeTerms()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void shouldReturnEmptyResultForBlankInput() {
        ContextQuery query = ContextQuery.parse("   ");

        assertThat(query.usableForFullText()).isFalse();
        assertThat(query.likeTerms()).isEmpty();
    }

    @Test
    void shouldEscapeLikeWildcards() {
        assertThat(ContextQuery.escapeLike("50%_off\\x")).isEqualTo("50\\%\\_off\\\\x");
    }

    @Test
    void shouldCapTermCountForVeryLongTask() {
        ContextQuery query = ContextQuery.parse("这是一段很长很长很长的中文任务描述".repeat(40));

        assertThat(query.booleanQuery().split(" ")).hasSizeLessThanOrEqualTo(24);
    }

    @Test
    void shouldDropStopBigrams() {
        ContextQuery query = ContextQuery.parse("我们需要进行一个优化");

        assertThat(Arrays.asList(query.booleanQuery().split(" "))).doesNotContain("我们", "需要", "进行", "一个");
        assertThat(query.booleanQuery()).contains("优化");
    }
}
