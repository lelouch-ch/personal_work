package com.example.pwt.memory.application;

import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceEntity;
import com.example.pwt.memory.infrastructure.MemoryReviewEntity;
import java.util.List;

/**
 * 记忆详情：正文、证据与审核留痕（设计文档 16.3「不要只提供一个批准按钮而隐藏证据」）。
 *
 * @param memory   记忆本体
 * @param evidence 证据列表
 * @param reviews  审核历史
 * @author pwt
 */
public record MemoryDetail(MemoryEntity memory, List<MemoryEvidenceEntity> evidence,
                           List<MemoryReviewEntity> reviews) {
}
