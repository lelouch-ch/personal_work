package com.example.pwt.memory.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 审核动作的附言，对应设计文档 10.4 的 {@code comment}。
 *
 * @param comment 备注
 * @author pwt
 */
public record MemoryReviewRequest(
    @Size(max = 2000, message = "备注长度不能超过 2000")
    String comment) {
}
