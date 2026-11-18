package com.example.pwt.mcp;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具入参取值：把协议层的 {@code Map<String,Object>} 转成带类型的参数
 * （设计文档 9.4 的 Schema 用 snake_case 字段名）。
 *
 * <p>必填与类型在这里校验，长度与取值范围交给各业务服务：服务层已经在做
 * （如 {@code WorkLogService} 的标题 300 字、{@code MemoryService} 的正文 10000 字），
 * 在这里再抄一份会变成两处规则各处漂移的源头。这里只负责“能不能拿到、类型对不对”。
 *
 * <p>错误一律是 {@link ErrorCode#VALIDATION_ERROR} 且消息里带字段名，
 * 让 Agent 能从一次失败里知道该改哪个参数。
 *
 * @param values 原始参数，可为空
 * @author pwt
 */
record McpArgs(Map<String, Object> values) {

    McpArgs {
        values = values == null ? Map.of() : values;
    }

    /**
     * 取必填字符串。
     *
     * @param name 字段名
     * @return 去掉首尾空白的值
     * @throws BizException 缺失或为空白
     */
    String requiredString(String name) {
        String value = optionalString(name);
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * 选填字符串，空白按未提供处理。
     *
     * @param name 字段名
     * @return 值，未提供时为空
     */
    String optionalString(String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * 取必填 UUID。
     *
     * @param name 字段名
     * @return UUID
     * @throws BizException 缺失或格式非法
     */
    UUID requiredUuid(String name) {
        UUID value = optionalUuid(name);
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * 选填 UUID。
     *
     * @param name 字段名
     * @return UUID，未提供时为空
     * @throws BizException 格式非法
     */
    UUID optionalUuid(String name) {
        String value = optionalString(name);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(name, "需为 UUID", e);
        }
    }

    /**
     * 取必填时间，接受 ISO-8601 带时区的写法。
     *
     * @param name 字段名
     * @return 时间点
     * @throws BizException 缺失或格式非法
     */
    Instant requiredInstant(String name) {
        Instant value = optionalInstant(name);
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * 选填时间。
     *
     * @param name 字段名
     * @return 时间点，未提供时为空
     * @throws BizException 格式非法
     */
    Instant optionalInstant(String name) {
        String value = optionalString(name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(name, "需为 ISO-8601 时间，如 2026-11-18T09:30:00Z", e);
        }
    }

    /**
     * 取必填整数。
     *
     * @param name 字段名
     * @return 整数值
     * @throws BizException 缺失或不是整数
     */
    int requiredInt(String name) {
        Integer value = optionalInt(name);
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * 选填整数。
     *
     * @param name 字段名
     * @return 值，未提供时为空
     * @throws BizException 不是数字
     */
    Integer optionalInt(String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            throw invalid(name, "需为整数", e);
        }
    }

    /**
     * 选填小数。
     *
     * @param name 字段名
     * @return 值，未提供时为空
     * @throws BizException 不是数字
     */
    Double optionalDouble(String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            throw invalid(name, "需为数字", e);
        }
    }

    /**
     * 选填字符串数组。
     *
     * @param name 字段名
     * @return 值列表，未提供时为空列表
     * @throws BizException 传入的不是数组
     */
    List<String> stringList(String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(name, "需为字符串数组", null);
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).strip();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 选填布尔。
     *
     * @param name 字段名
     * @return 值，未提供时为空（表示“由服务端取默认值”）
     * @throws BizException 既不是布尔也不是 true/false 字面量
     */
    Boolean optionalBoolean(String name) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        String text = String.valueOf(raw).strip();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw invalid(name, "需为 true 或 false", null);
    }

    private static BizException missing(String name) {
        return new BizException(ErrorCode.VALIDATION_ERROR, "参数 " + name + " 不能为空");
    }

    private static BizException invalid(String name, String expectation, Throwable cause) {
        String message = "参数 " + name + " 非法：" + expectation;
        return cause == null ? new BizException(ErrorCode.VALIDATION_ERROR, message)
            : new BizException(ErrorCode.VALIDATION_ERROR, message, cause);
    }
}
