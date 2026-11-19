package com.example.pwt.skill.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建 / 更新 Skill 的请求（设计文档 10.2）。
 *
 * <p>{@code slug} 只在创建时生效：它是 Agent 引用 Skill 的稳定标识，
 * 允许中文名称变更而标识不变，历史上下文里的引用才不会断。更新时以现有值为准。
 *
 * @param slug            稳定标识，如 {@code code-review}，创建时必填
 * @param name            显示名称，允许中文
 * @param description     一句话用途说明，是 Agent 选型依据
 * @param contentMd       Markdown 正文
 * @param visibility      可见性，为空表示 private；非 Owner 不得设为 public
 * @param sourceMemoryIds 来源记忆 ID，用于回溯这条 Skill 基于哪些经验写成
 * @author pwt
 */
public record SkillRequest(
    @Size(max = 200, message = "slug 长度不能超过 200")
    String slug,

    @NotNull(message = "名称不能为空")
    @Size(min = 1, max = 200, message = "名称长度需在 1 到 200 之间")
    String name,

    @Size(max = 2000, message = "说明长度不能超过 2000")
    String description,

    @NotNull(message = "正文不能为空")
    @Size(min = 1, max = 30000, message = "正文长度需在 1 到 30000 之间")
    String contentMd,

    String visibility,

    @Size(max = 20, message = "来源记忆数量不能超过 20")
    List<String> sourceMemoryIds) {
}
