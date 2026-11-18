package com.example.pwt.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 敏感信息检测测试（设计文档 15.1，验收用例 4 的 L3 部分）。
 *
 * @author pwt
 */
class SensitiveFinderTest {

    @Test
    void plainTextShouldNotBeFlagged() {
        assertThat(SensitiveFinder.scan("完成上传链路的重试改造，涉及 12 个接口回归。")).isEmpty();
    }

    @Test
    void credentialAssignmentsShouldBeL3() {
        for (String text : List.of(
            "password = Hunter2!x",
            "把 API_KEY: sk-abcdefghijklmnopqrstuvwxyz123456 填进配置",
            "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWN0In0.abcdefghijklmnop",
            "-----BEGIN RSA PRIVATE KEY-----",
            "AKIAIOSFODNN7EXAMPLE",
            "pwt_0123456789abcdef0123456789abcdef")) {
            List<SensitiveFinder.Finding> findings = SensitiveFinder.scan(text);

            assertThat(findings).as("%s 应命中", text).isNotEmpty();
            assertThat(SensitiveFinder.highestRisk(findings)).as("%s 应为 L3", text).isEqualTo(RiskLevel.L3);
        }
    }

    @Test
    void identityNumbersShouldBeL2() {
        List<SensitiveFinder.Finding> findings = SensitiveFinder.scan("身份证号 110101199003074571 需要脱敏");

        assertThat(SensitiveFinder.highestRisk(findings)).isEqualTo(RiskLevel.L2);
        assertThat(findings).allMatch(f -> f.category() == SensitiveFinder.Category.HIGH_SENSITIVE_ID);
    }

    @Test
    void findingToStringMustNotLeakSecret() {
        SensitiveFinder.Finding finding = SensitiveFinder.scan("password = SuperSecret123").get(0);

        assertThat(finding.matched()).contains("SuperSecret123");
        assertThat(finding.toString()).doesNotContain("SuperSecret123");
    }

    @Test
    void nullOrBlankShouldYieldNoFindings() {
        assertThat(SensitiveFinder.scan(null)).isEmpty();
        assertThat(SensitiveFinder.scan("   ")).isEmpty();
        assertThat(SensitiveFinder.highestRisk(List.of())).isEqualTo(RiskLevel.L0);
    }

    @Test
    void credentialShouldOutrankIdentityNumber() {
        List<SensitiveFinder.Finding> findings = SensitiveFinder.scan(
            "身份证号 110101199003074571，登录口令 password=abc123456");

        assertThat(SensitiveFinder.highestRisk(findings)).isEqualTo(RiskLevel.L3);
    }
}
