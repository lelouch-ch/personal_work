package com.example.pwt.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 内容规范化与去重键测试（设计文档 6.5、8.4 第 10 条）。
 *
 * @author pwt
 */
class MemoryContentTest {

    @Test
    void normalizationShouldUnifyCaseWhitespaceAndAccents() {
        assertThat(MemoryContent.normalize("  Spring\u00a0Boot \r\n3.2  "))
            .isEqualTo("spring boot 3.2");
    }

    @Test
    void sameMeaningDifferentFormattingShouldHashEqual() {
        assertThat(MemoryContent.normalizedHash(MemoryType.FACT, "项目框架是 Spring Boot 3.2"))
            .isEqualTo(MemoryContent.normalizedHash(MemoryType.FACT, "  项目框架是\n  spring boot 3.2 "));
    }

    @Test
    void sameContentDifferentTypeShouldNotBeTreatedAsDuplicate() {
        // 6.5：精确去重限定“同一用户、同类型”
        assertThat(MemoryContent.normalizedHash(MemoryType.FACT, "先给可运行方案"))
            .isNotEqualTo(MemoryContent.normalizedHash(MemoryType.PREFERENCE, "先给可运行方案"));
    }

    @Test
    void hashShouldMatchColumnWidth() {
        // normalized_hash 为 varchar(128)，SHA-256 十六进制为 64，按列宽收紧
        assertThat(MemoryContent.normalizedHash(MemoryType.FACT, "内容")).hasSize(64);
    }

    @Test
    void nullContentShouldNormalizeToEmpty() {
        assertThat(MemoryContent.normalize(null)).isEmpty();
    }

    @Test
    void differentContentShouldHashDifferently() {
        assertThat(MemoryContent.normalizedHash(MemoryType.FACT, "版本 3.1"))
            .isNotEqualTo(MemoryContent.normalizedHash(MemoryType.FACT, "版本 3.2"));
    }
}
