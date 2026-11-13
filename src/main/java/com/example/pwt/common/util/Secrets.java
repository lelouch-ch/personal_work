package com.example.pwt.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 凭证工具：生成随机密钥、计算 SHA-256 摘要、提取展示前缀。
 *
 * <p>设计文档 14.1 要求 Token 只在创建时明文返回一次，数据库仅存哈希。
 * 这里使用无密钥 SHA-256 而非 BCrypt：鉴权路径需要按哈希等值查库，
 * 必须可索引且不能用随机盐；Agent Token 为 256 bit 随机值，不存在弱口令暴力猜测问题。
 * 设计文档 18.2 的“Token 哈希密钥”通过 {@link #hash(String, String)} 的 pepper 参数满足。
 *
 * @author pwt
 */
public final class Secrets {

    /** 随机密钥字节数，对应 256 bit 熵。 */
    private static final int SECRET_BYTES = 32;

    /** 明文密钥前缀，便于人工识别来源。 */
    public static final String TOKEN_PREFIX = "pwt_";

    /** 展示前缀长度。 */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /**
     * 生成带前缀的随机明文密钥。
     *
     * @return 明文密钥，仅应在创建响应中出现一次
     */
    public static String generateToken() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + HexFormat.of().withLowerCase().formatHex(bytes);
    }

    /**
     * 计算密钥摘要。
     *
     * @param secret 明文密钥
     * @return 十六进制 SHA-256 摘要
     */
    public static String hash(String secret) {
        return hash(secret, null);
    }

    /**
     * 计算带 pepper 的密钥摘要。
     *
     * @param secret 明文密钥，非空
     * @param pepper 服务端密钥，可为空；来自环境变量，不得入库或写日志
     * @return 十六进制 SHA-256 摘要
     */
    public static String hash(String secret, String pepper) {
        if (secret == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        MessageDigest digest = newDigest();
        digest.update(secret.getBytes(StandardCharsets.UTF_8));
        if (pepper != null && !pepper.isBlank()) {
            digest.update((byte) ':');
            digest.update(pepper.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().withLowerCase().formatHex(digest.digest());
    }

    /**
     * 提取用于列表展示的密钥前缀，避免回显完整明文。
     *
     * @param secret 明文密钥
     * @return 形如 {@code pwt_ab12cd} 的展示串
     */
    public static String displayPrefix(String secret) {
        if (secret == null || secret.length() <= DISPLAY_PREFIX_LENGTH) {
            return secret;
        }
        return secret.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
