package com.example.pwt.memory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息检测（设计文档 15.1）。
 *
 * <p>检测结果分两档：命中凭证/密钥类判 L3，不得进入普通记忆；命中身份与账号类判 L2，默认人工审核。
 * 检测在模型调用前和模型输出后各执行一次（12.3），因此本类只做纯函数判定，不依赖外部服务。
 *
 * <p>{@link Finding#sample()} 特意只保留极短的可见片段：日志与审核界面都不得回显原始秘密。
 *
 * @author pwt
 */
public final class SensitiveFinder {

    /**
     * 凭证与密钥：显式赋值形式（password/token/secret/key = xxx）、PEM 私钥块、
     * OpenAI 风格 sk- 密钥、AWS Access Key、JWT、PWT 自有 API Key。
     */
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|cookie|authorization)"
            + "\\s*[:=]\\s*\\S+"
            + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
            + "|\\bsk-[A-Za-z0-9_-]{16,}\\b"
            + "|\\bAKIA[0-9A-Z]{16}\\b"
            + "|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"
            + "|\\bpwt_[0-9a-f]{32,}\\b");

    /**
     * 高敏感标识：中国大陆身份证号、银行卡号（13-19 位连续数字）、IBAN 风格串。
     */
    private static final Pattern HIGH_SENSITIVE_ID = Pattern.compile(
        "\\b\\d{17}[\\dXx]\\b"
            + "|\\b\\d{13,19}\\b"
            + "|\\b[A-Z]{2}\\d{2}[A-Z]{4}\\d{10}\\b");

    private SensitiveFinder() {
    }

    /**
     * 扫描文本中的敏感信息。
     *
     * @param text 待检测内容，可为空
     * @return 命中列表，未命中时为空列表
     */
    public static List<Finding> scan(String text) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return findings;
        }
        collect(findings, CREDENTIAL, Category.CREDENTIAL, text);
        collect(findings, HIGH_SENSITIVE_ID, Category.HIGH_SENSITIVE_ID, text);
        return findings;
    }

    /**
     * 给出这批命中的最高风险等级，用于与分类结果合并（设计文档 6.2「风险级别不能由 LLM 单独决定」）。
     *
     * @param findings 命中列表
     * @return 无命中时为 {@link RiskLevel#L0}
     */
    public static RiskLevel highestRisk(List<Finding> findings) {
        RiskLevel highest = RiskLevel.L0;
        for (Finding finding : findings) {
            highest = highest.max(finding.riskLevel());
        }
        return highest;
    }

    private static void collect(List<Finding> findings, Pattern pattern, Category category, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            findings.add(new Finding(category, matcher.group(), sample(matcher.group()), patternRisk(category)));
        }
    }

    private static RiskLevel patternRisk(Category category) {
        return category == Category.CREDENTIAL ? RiskLevel.L3 : RiskLevel.L2;
    }

    private static String sample(String value) {
        String stripped = value.strip();
        return stripped.length() <= 6 ? stripped : stripped.substring(0, 6) + "…";
    }

    /**
     * 敏感信息类别。
     */
    public enum Category {

        /** 凭证与密钥。 */
        CREDENTIAL,
        /** 证件号、银行账号等高敏感标识。 */
        HIGH_SENSITIVE_ID
    }

    /**
     * 一条命中结果。
     *
     * @param category   类别
     * @param matched    命中的原始片段，只在内存中传递，不写日志
     * @param sample     可展示的极短片段
     * @param riskLevel  该命中对应的最低风险等级
     * @author pwt
     */
    public record Finding(Category category, String matched, String sample, RiskLevel riskLevel) {

        @Override
        public String toString() {
            // 默认 record toString 会带出 matched，可能把秘密写进日志
            return "Finding{category=" + category + ", sample=" + sample + ", riskLevel=" + riskLevel + '}';
        }
    }
}
