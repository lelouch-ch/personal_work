package com.example.pwt.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * UUIDv7 生成测试。
 *
 * @author pwt
 */
class UuidsTest {

    @Test
    void shouldSetVersionAndVariantBits() {
        UUID uuid = Uuids.randomUuidv7();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void shouldCarryCurrentTimestampInMostSignificantBits() {
        long before = System.currentTimeMillis();
        UUID uuid = Uuids.randomUuidv7();
        long after = System.currentTimeMillis();

        long timestamp = uuid.getMostSignificantBits() >>> 16;

        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void shouldGenerateDistinctValues() {
        Set<UUID> generated = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            generated.add(Uuids.randomUuidv7());
        }

        assertThat(generated).hasSize(5000);
    }

    @Test
    void shouldSortByTimeWithinSameProcess() {
        UUID earlier = Uuids.randomUuidv7();
        UUID later = Uuids.randomUuidv7();

        assertThat(earlier.getMostSignificantBits() >>> 16)
            .isLessThanOrEqualTo(later.getMostSignificantBits() >>> 16);
    }

    @Test
    void shouldRenderLowerCaseCanonicalString() {
        String value = Uuids.randomUuidv7String();

        assertThat(value).hasSize(36).matches("[0-9a-f-]{36}");
    }

    @Test
    void shouldParseWithSurroundingWhitespace() {
        String value = Uuids.randomUuidv7String();

        assertThat(Uuids.parse("  " + value + " ").toString()).isEqualTo(value);
    }

    @Test
    void shouldRejectBlankInputWhenParsing() {
        assertThatThrownBy(() -> Uuids.parse(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("uuid must not be blank");
    }
}
