package com.example.pwt.work.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;

/**
 * 创建或更新项目的请求。
 *
 * @param name        项目名称，创建时必填
 * @param description 项目说明
 * @param status      状态：active / paused / completed / archived
 * @param visibility  可见性：private / agent / public，仅 Owner 可设为 public
 * @param startedAt   开始日期
 * @param endedAt     结束日期
 * @param metadata    扩展元数据
 * @author pwt
 */
public record ProjectRequest(
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 200, message = "项目名称长度不能超过 200")
    String name,

    @Size(max = 5000, message = "项目说明长度不能超过 5000")
    String description,

    String status,

    String visibility,

    LocalDate startedAt,

    LocalDate endedAt,

    Map<String, Object> metadata) {
}
