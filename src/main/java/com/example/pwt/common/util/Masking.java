package com.example.pwt.common.util;

import java.util.regex.Pattern;

/**
 * 敏感串脱敏：把可能混入日志与错误信息的凭证替换为占位符。
 *
 * <p>设计文档 15.1 要求“只记录必要的拒绝原因，不把原始秘密写入日志”。
 * 任务表 {@code last_error}、审计详情与日志都可能带出异常原文，因此在写出口处统一脱敏，
 * 而不是依赖每个调用点自觉处理。
 *
 * @author pwt
 */
public final class Masking {

    /** 占位符。 */
    public static final String MASK = "***";

    /** 单次处理的最大长度，超出部分直接截断，避免超长正文把日志撑爆。 */
    private static final int MAX_LENGTH = 1000;

    /**
     * 常见凭证形态：Bearer 令牌、AWS 风格密钥、OpenAI 风格密钥、JWT、十六进制长串。
     * 只匹配“看起来像密钥”的连续串，避免把普通中文正文整段抹掉。
     *
     * <p>{@code Bearer <token>} 必须单独一条并排在赋值式之前：赋值式的 {@code \S+} 只吃掉
     * 冒号后的第一个词，对 "Authorization: Bearer xxx" 吃掉的是 "Bearer" 本身，
     * 真正的令牌反而原样留下（单测抓到过这个泄漏）。
     */
    private static final Pattern[] SECRET_PATTERNS = {
        Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Pattern.compile("(?i)(bearer|basic|authorization|x-api-key|api[-_]?key|secret|token|password)\\s*[=:]\\s*\\S+"),
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
        Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
        Pattern.compile("\\bpwt_[0-9a-f]{32,}\\b"),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\b"),
        Pattern.compile("\\b[0-9a-fA-F]{32,}\\b")
    };

    private Masking() {
    }

    /**
     * 对文本脱敏并限长。
     *
     * @param text 原文，可为空
     * @return 脱敏后的文本，入参为空时返回 {@code null}
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH) + "...(truncated)";
        }
        return result;
    }
}
