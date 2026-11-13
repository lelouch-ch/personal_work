package com.example.pwt.work.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 创建工作记录的命令对象，承载手动录入与 Agent 提交两种来源。
 *
 * @param projectId      关联项目，可为空
 * @param title          标题
 * @param summary        摘要
 * @param content        正文，保留原始内容
 * @param workType       工作类型，可为空表示 general
 * @param occurredAt     发生时间
 * @param tags           标签，可为空
 * @param visibility     可见性，可为空表示 private
 * @param idempotencyKey 幂等键，可为空
 * @author pwt
 */
public record WorkLogCommand(UUID projectId, String title, String summary, String content, String workType,
                             Instant occurredAt, List<String> tags, String visibility, String idempotencyKey) {
}
