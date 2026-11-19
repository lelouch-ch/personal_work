package com.example.pwt.mcp;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具入参 Schema 构造（设计文档 9.4）。
 *
 * <p>只负责把字段描述拼成 JSON Schema，不做校验：字段长度与取值范围的校验在服务层，
 * 这里写出来的 Schema 是给客户端看的自描述信息，也是它填参数的依据，
 * 因此每个字段都要带中文说明——Agent 只能从 description 判断该填什么。
 *
 * @author pwt
 */
final class McpSchemas {

    /** 记忆类型取值，与 9.4 的枚举一致。 */
    static final List<String> MEMORY_TYPES =
        List.of("fact", "preference", "experience", "decision", "constraint", "goal");

    /**
     * 工具可提交的可见性取值。
     *
     * <p>刻意不含 {@code public}：9.6 规定工具不能把内容直接发布为公开，
     * Schema 里就不该给出这个选项，否则客户端要先猜、再被服务端拒一次。
     */
    static final List<String> AGENT_VISIBILITIES = List.of("private", "agent");

    /** 工作类型取值，与 {@code pt_work_log.work_type} 的 CHECK 一致。 */
    static final List<String> WORK_TYPES =
        List.of("development", "research", "meeting", "operation", "learning", "general");

    private McpSchemas() {
    }

    /**
     * 组装对象型入参 Schema。
     *
     * @param properties 字段描述
     * @param required   必填字段名
     * @return Schema
     */
    static JsonSchema objectSchema(Map<String, Object> properties, List<String> required) {
        return new JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    /**
     * 字符串字段。
     *
     * @param description 说明，客户端唯一能读到的提示
     * @param format      JSON Schema format，可为空
     * @return 字段描述
     */
    static Map<String, Object> string(String description, String format) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        if (format != null) {
            schema.put("format", format);
        }
        return schema;
    }

    /**
     * 受限取值的字符串字段。
     *
     * @param values      允许取值
     * @param description 说明
     * @return 字段描述
     */
    static Map<String, Object> stringEnum(List<String> values, String description) {
        Map<String, Object> schema = new LinkedHashMap<>(string(description, null));
        schema.put("enum", values);
        return schema;
    }

    /**
     * 字符串数组字段。
     *
     * @param description 说明
     * @param itemEnum    元素允许取值，为空表示不限
     * @return 字段描述
     */
    static Map<String, Object> stringArray(String description, List<String> itemEnum) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        if (itemEnum != null) {
            items.put("enum", itemEnum);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", items);
        return schema;
    }

    /**
     * UUID 数组字段，用于 9.4 的 {@code skill_ids}。
     *
     * <p>刻意不复用 {@link #stringArray}：那个方法的元素是自由文本（标签、类型名），
     * 而这里必须带 {@code format: uuid}，否则客户端会把标题之类的字符串塞进来，
     * 到服务端才失败一次。
     *
     * @param description 说明
     * @param maxItems    元素个数上限，可为空表示不限
     * @return 字段描述
     */
    static Map<String, Object> uuidArray(String description, Integer maxItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", Map.of("type", "string", "format", "uuid"));
        if (maxItems != null) {
            schema.put("maxItems", maxItems);
        }
        return schema;
    }

    /**
     * 整数字段。
     *
     * @param description 说明
     * @return 字段描述
     */
    static Map<String, Object> integer(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    /**
     * 小数字段。
     *
     * @param description 说明
     * @param minimum     取值下限，可为空
     * @param maximum     取值上限，可为空
     * @return 字段描述
     */
    static Map<String, Object> number(String description, Double minimum, Double maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "number");
        schema.put("description", description);
        if (minimum != null) {
            schema.put("minimum", minimum);
        }
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        return schema;
    }

    /**
     * 布尔字段。
     *
     * @param description 说明
     * @return 字段描述
     */
    static Map<String, Object> bool(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }
}
