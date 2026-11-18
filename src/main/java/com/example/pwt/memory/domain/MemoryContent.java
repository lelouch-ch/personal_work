package com.example.pwt.memory.domain;

import com.example.pwt.common.util.Secrets;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * 记忆正文的规范化与精确去重键（设计文档 6.5）。
 *
 * <p>MySQL 表排序规则是 {@code utf8mb4_0900_ai_ci}，大小写与重音不敏感，
 * 不能用来判定“规范化内容唯一”（8.4 第 10 条），因此去重键由应用层计算，
 * 存进 {@code normalized_hash}（ascii_bin）后按字节精确比较。
 *
 * @author pwt
 */
public final class MemoryContent {

    /**
     * 连续空白折叠为单个空格。
     *
     * <p>不能只用 {@code \s}：Java 的 {@code \s} 不含 U+00A0（不换行空格）与 U+3000（全角空格），
     * 而这两者恰好常见于从网页/文档粘贴的中文正文，漏掉它们会让同一条记忆算出不同的哈希，
     * 去重静默失效。
     */
    private static final String WHITESPACE_RUN = "[\\s\\u00A0\\u2007\\u202F\\u3000\\u200B]+";

    private MemoryContent() {
    }

    /**
     * 规范化正文：统一换行、NFC 归一、去首尾空白、折叠内部空白。
     *
     * <p>不做大小写转换以外的语义改写：中文没有词形变化，英文只统一小写，
     * 避免过度归一让两条实际含义不同的记忆被判为重复。
     *
     * @param content 原始内容，可为空
     * @return 规范化结果，入参为空时返回空串
     */
    public static String normalize(String content) {
        if (content == null) {
            return "";
        }
        String unified = content.replace("\r\n", "\n").replace('\r', '\n');
        String nfc = Normalizer.normalize(unified, Normalizer.Form.NFC);
        return nfc.strip().replaceAll(WHITESPACE_RUN, " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 计算去重键。
     *
     * @param content 原始内容
     * @return SHA-256 十六进制摘要，长度 64，与列宽一致
     */
    public static String normalizedHash(String content) {
        return Secrets.hash(normalize(content));
    }

    /**
     * 计算带类型维度的去重键：同一用户、同类型、规范化内容一致才算重复（设计文档 6.5）。
     *
     * @param type    记忆类型
     * @param content 原始内容
     * @return SHA-256 十六进制摘要
     */
    public static String normalizedHash(MemoryType type, String content) {
        String combined = type.getValue() + "\u0000" + normalize(content);
        return Secrets.hash(combined);
    }

    /**
     * 按字节长度判断内容是否可存入 {@code mediumtext} 列。
     *
     * @param content     内容
     * @param maxBytes    上限字节数
     * @return true 表示未超限
     */
    public static boolean withinBytes(String content, int maxBytes) {
        return content == null || content.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
