package com.example.pwt.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID 生成工具。
 *
 * <p>设计文档 8.1 要求主键使用 UUIDv7（时间有序，利于 InnoDB 顺序写入与索引局部性）。
 * JDK 17 尚无 {@code UUID} v7 工厂方法，此处按 RFC 9562 结构自行生成。
 *
 * @author pwt
 */
public final class Uuids {

    /** UUIDv7 版本号位。 */
    private static final int VERSION_SEVEN = 7;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {
    }

    /**
     * 生成 UUIDv7。
     *
     * <p>布局：48 bit 毫秒时间戳 + 12 bit 随机数（rand_a）+ 4 bit 版本 + 30 bit 随机数（rand_b 与 variant）。
     * 同一毫秒内的相对顺序由 rand_a 提供，不保证严格单调递增。
     *
     * @return UUIDv7 实例
     */
    public static UUID randomUuidv7() {
        long timestamp = System.currentTimeMillis();
        long msb = (timestamp << 16) & 0xFFFF_FFFF_FFFF_0000L;
        msb |= (nextRandomBits(12));
        msb |= ((long) VERSION_SEVEN << 12);
        long lsb = 0b10L << 62;
        lsb |= nextRandomBits(62);
        return new UUID(msb, lsb);
    }

    /**
     * 生成小写标准字符串形式的 UUIDv7，直接用于 {@code char(36)} 主键。
     *
     * @return UUID 字符串
     */
    public static String randomUuidv7String() {
        return randomUuidv7().toString();
    }

    /**
     * 解析并校验 UUID 字符串。
     *
     * @param value 待解析字符串
     * @return UUID 实例
     * @throws IllegalArgumentException 格式非法时抛出
     */
    public static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("uuid must not be blank");
        }
        return UUID.fromString(value.trim());
    }

    private static long nextRandomBits(int bits) {
        long value = RANDOM.nextLong() >>> (64 - bits);
        return value & ((1L << bits) - 1);
    }
}
