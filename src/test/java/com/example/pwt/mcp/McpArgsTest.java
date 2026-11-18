package com.example.pwt.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MCP 入参解析测试：错误必须点名到具体字段。
 *
 * <p>Agent 只能靠错误信息决定改哪个参数，只回“参数非法”等于让它重猜一遍，
 * 因此这里逐条钉住“缺什么说什么、错什么说什么”。
 *
 * @author pwt
 */
class McpArgsTest {

    @Test
    void shouldTreatBlankAsMissing() {
        McpArgs args = new McpArgs(Map.of("title", "   "));

        assertThat(args.optionalString("title")).isNull();
        assertThatThrownBy(() -> args.requiredString("title"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("title");
    }

    @Test
    void shouldAcceptNullArgumentMap() {
        McpArgs args = new McpArgs(null);

        assertThat(args.stringList("tags")).isEmpty();
        assertThat(args.optionalInt("limit")).isNull();
        assertThat(args.optionalBoolean("include_skills")).isNull();
    }

    @Test
    void shouldParseUuidAndInstant() {
        String id = UUID.randomUUID().toString();
        McpArgs args = new McpArgs(Map.of("project_id", " " + id + " ",
            "occurred_at", "2026-11-18T09:30:00Z"));

        assertThat(args.optionalUuid("project_id")).isEqualTo(UUID.fromString(id));
        assertThat(args.requiredInstant("occurred_at")).isEqualTo(Instant.parse("2026-11-18T09:30:00Z"));
    }

    @Test
    void shouldNameFieldWhenUuidMalformed() {
        McpArgs args = new McpArgs(Map.of("project_id", "not-a-uuid"));

        assertThatThrownBy(() -> args.optionalUuid("project_id"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("project_id")
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void shouldAcceptNumericStringsForIntAndDouble() {
        McpArgs args = new McpArgs(Map.of("limit", "12", "confidence", "0.85"));

        // 部分客户端把数字序列化成字符串，拒收会让本可成功的调用失败一次
        assertThat(args.optionalInt("limit")).isEqualTo(12);
        assertThat(args.optionalDouble("confidence")).isEqualTo(0.85D);
    }

    @Test
    void shouldRejectNonNumericValues() {
        McpArgs args = new McpArgs(Map.of("limit", "十", "confidence", "很高"));

        assertThatThrownBy(() -> args.optionalInt("limit"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("limit");
        assertThatThrownBy(() -> args.optionalDouble("confidence"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("confidence");
    }

    @Test
    void shouldDropBlankItemsFromStringList() {
        McpArgs args = new McpArgs(Map.of("tags", List.of(" MCP ", "", "  ", "m6")));

        assertThat(args.stringList("tags")).containsExactly("MCP", "m6");
    }

    @Test
    void shouldRejectScalarWhereListExpected() {
        McpArgs args = new McpArgs(Map.of("tags", "mcp"));

        assertThatThrownBy(() -> args.stringList("tags"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("tags");
    }

    @Test
    void shouldParseBooleanLiteralsOnly() {
        assertThat(new McpArgs(Map.of("include_skills", "TRUE")).optionalBoolean("include_skills"))
            .isTrue();
        assertThat(new McpArgs(Map.of("include_skills", false)).optionalBoolean("include_skills"))
            .isFalse();
        assertThatThrownBy(() -> new McpArgs(Map.of("include_skills", "maybe"))
            .optionalBoolean("include_skills"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("include_skills");
    }
}
