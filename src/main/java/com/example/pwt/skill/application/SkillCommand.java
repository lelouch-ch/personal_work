package com.example.pwt.skill.application;

import java.util.List;

/**
 * Skill 写入命令（设计文档 10.2 的 POST / PUT /api/v1/skills）。
 *
 * @param slug            稳定标识，必填，创建后不可改
 * @param name            显示名称，必填
 * @param description     用途说明，可为空
 * @param contentMd       Markdown 正文，必填
 * @param visibility      可见性，可为空表示 private
 * @param sourceMemoryIds 来源记忆 ID，可为空
 * @author pwt
 */
public record SkillCommand(String slug, String name, String description, String contentMd,
                           String visibility, List<String> sourceMemoryIds) {
}
