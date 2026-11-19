package com.example.pwt.context.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 新建风格规则的请求。
 *
 * @param ruleKey    规则键，每用户唯一，如 {@code answer_format}
 * @param ruleValue  规则值，JSON 结构由键自行约定（字符串、数组或对象均可）
 * @param confidence 置信度 0~1，可为空
 * @param visibility 可见性，为空表示 private；非 Owner 不得设为 public
 * @author pwt
 */
public record StyleRuleRequest(
    @NotNull(message = "规则键不能为空")
    @Size(min = 1, max = 120, message = "规则键长度需在 1 到 120 之间")
    String ruleKey,

    Object ruleValue,

    BigDecimal confidence,

    String visibility) {
}
