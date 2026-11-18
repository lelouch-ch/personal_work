package com.example.pwt.memory.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修正记忆正文的请求，对应审核界面的“编辑后批准”（设计文档 16.3、场景 C）。
 *
 * @param content 新正文
 * @param summary 新摘要，为空表示沿用
 * @param approve 是否修正后直接批准
 * @author pwt
 */
public record MemoryReviseRequest(
    @NotNull(message = "记忆正文不能为空")
    @Size(min = 1, max = 10000, message = "记忆正文长度需在 1 到 10000 之间")
    String content,

    @Size(max = 1000, message = "摘要长度不能超过 1000")
    String summary,

    Boolean approve) {
}
