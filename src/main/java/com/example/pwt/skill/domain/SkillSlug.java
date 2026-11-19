package com.example.pwt.skill.domain;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * Skill 的 slug 规则（设计文档 8.3 的 {@code uq_skill_user_slug_version}）。
 *
 * <p>slug 是 Agent 引用 Skill 用的稳定标识，与可改的中文名分开：中文名改成
 * 「代码评审清单」而 slug 仍是 {@code code-review}，历史上下文里的引用才不会断。
 *
 * <p>要求 Owner 显式给出、不自动从名称推导：名称允许中文，按 ASCII 推导的结果
 * 往往只剩一串连字符，既不稳定也不可辨识，不如直接报错让 Owner 起一个。
 *
 * @author pwt
 */
public final class SkillSlug {

    /** 小写字母、数字与连字符，必须以字母或数字起止，最长 200 与列宽一致。 */
    private static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,198}[a-z0-9])?$");

    /** 连续连字符：允许但不鼓励，归一时收敛成一个，避免 hand--off 与 hand-off 变成两个 Skill。 */
    private static final Pattern REPEATED_HYPHENS = Pattern.compile("-{2,}");

    private SkillSlug() {
    }

    /**
     * 归一并校验 slug。
     *
     * @param raw 原始输入，大小写与首尾空白不限
     * @return 归一后的 slug
     * @throws BizException 归一后不合法
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "slug 不能为空");
        }
        String value = REPEATED_HYPHENS.matcher(raw.trim().toLowerCase()).replaceAll("-");
        if (!VALID.matcher(value).matches()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "slug 只能由小写字母、数字与连字符组成，且以字母或数字起止（如 code-review）");
        }
        return value;
    }
}
