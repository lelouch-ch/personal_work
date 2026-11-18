package com.example.pwt.common.util;

/**
 * Token 估算：设计文档 11.5 允许先用字符估算，后续再接模型 tokenizer。
 *
 * <p>分档计数而不是按 {@code 长度 / 4} 统一折算：ngram 分词与主流 BPE 词表对中日韩文本
 * 的切分粒度明显更细，中文常按字或双字成 token，统一按 4 字符折算会低估一半以上。
 * 实测低估会让 {@code build_context} 在预算已超时仍然继续注入，直接撑爆调用方模型的上下文窗口。
 *
 * <p>这里给出的是保守上界：宁可少注入一条补充记忆，也不要让 Agent 拿到被截断的上下文。
 * 精确值等接入具体模型后由 tokenizer 适配器替换，调用方不依赖本类的具体系数。
 *
 * @author pwt
 */
public final class TokenEstimator {

    /** 非 ASCII 字符（中日韩等）的 token 估算系数，按每字符 1 token 计。 */
    private static final double CJK_TOKENS_PER_CHAR = 1.0D;

    /** ASCII 文本的 token 估算系数，约每 4 个字符 1 token。 */
    private static final double ASCII_TOKENS_PER_CHAR = 0.25D;

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，为空按 0 计
     * @return 估算 token 数，向上取整
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wideChars = 0;
        int asciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x80) {
                asciiChars++;
            } else {
                wideChars++;
            }
        }
        double raw = wideChars * CJK_TOKENS_PER_CHAR + asciiChars * ASCII_TOKENS_PER_CHAR;
        return (int) Math.ceil(raw);
    }

    /**
     * 估算多段文本的 token 总数。
     *
     * @param texts 文本列表，元素可为空
     * @return 估算 token 数
     */
    public static int estimateAll(Iterable<String> texts) {
        int total = 0;
        if (texts != null) {
            for (String text : texts) {
                total += estimate(text);
            }
        }
        return total;
    }
}
