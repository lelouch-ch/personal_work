package com.example.pwt.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 冲突检测规则测试（设计文档 6.5、6.3 第 6 条）。
 *
 * @author pwt
 */
class ConflictDetectorTest {

    @Test
    void oppositePolarityShouldConflict() {
        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect("偏好不要先给结论",
            List.of("偏好先给结论"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).reason()).contains("极性相反");
    }

    @Test
    void sameSubjectDifferentValueShouldConflict() {
        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect("项目框架是 spring boot 3.2",
            List.of("项目框架是 spring boot 3.1"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).reason()).contains("取值不同");
    }

    @Test
    void identicalContentIsDuplicateNotConflict() {
        assertThat(ConflictDetector.detect("项目框架是 spring boot 3.2",
            List.of("项目框架是 Spring Boot 3.2"))).isEmpty();
    }

    @Test
    void unrelatedContentShouldNotConflict() {
        assertThat(ConflictDetector.detect("偏好使用中文回复",
            List.of("项目部署在内网 192.168.124.108 的 mysql 9.3"))).isEmpty();
    }

    @Test
    void bothNegatedOrBothAffirmativeShouldNotConflict() {
        assertThat(ConflictDetector.detect("不喜欢写单元测试", List.of("讨厌写单元测试"))).isEmpty();
    }

    @Test
    void differingTailsWithoutDigitsShouldNotConflict() {
        // 同一前缀但尾部无数字：属于同主题的不同描述，不足以判定相反取值
        assertThat(ConflictDetector.detect("项目使用 spring boot 框架",
            List.of("项目使用 spring cloud 组件"))).isEmpty();
    }

    @Test
    void blankInputShouldReturnNoConflicts() {
        assertThat(ConflictDetector.detect(null, List.of("内容"))).isEmpty();
        assertThat(ConflictDetector.detect("内容", null)).isEmpty();
        assertThat(ConflictDetector.detect("内容", List.of())).isEmpty();
    }

    @Test
    void normalizationShouldNotAffectJudgement() {
        assertThat(ConflictDetector.detect("  项目框架是   SPRING BOOT 3.2 ",
            List.of("项目框架是 spring boot 3.1"))).hasSize(1);
    }
}
