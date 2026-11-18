package com.example.pwt.memory.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 长期记忆持久化对象，映射 {@code pt_memory}。
 *
 * <p>提取模型与提示词版本等元数据不落在本表：表结构没有对应列，按设计文档 6.6
 * 记在 {@code pt_memory_evidence.source_metadata}，与证据同源便于核对。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName("pt_memory")
public class MemoryEntity {

    /** 记忆 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 记忆类型，取值见 {@link com.example.pwt.memory.domain.MemoryType}。 */
    private String memoryType;

    /** 记忆正文。 */
    private String content;

    /** 一句话摘要，用于审核界面与上下文注入。 */
    private String summary;

    /** 规范化内容摘要，用于精确去重。 */
    private String normalizedHash;

    /** 置信度，0 到 1。 */
    private BigDecimal confidence;

    /** 重要度，1 到 5。 */
    private Integer importance;

    /** 风险等级：L0 到 L3。 */
    private String riskLevel;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 状态：candidate / approved / rejected / archived / superseded。 */
    private String status;

    /** 来源：manual / agent / import / system。 */
    private String sourceType;

    /** 来源对象 ID，通常是工作记录 ID。 */
    private String sourceId;

    /**
     * 归属项目，取自来源工作记录。
     *
     * <p>为 {@code null} 表示项目未知（人工提交且未带来源工作记录的记忆），
     * 按项目过滤时不参与项目匹配打分，而不是被直接排除（设计文档 V10 迁移说明）。
     */
    private String projectId;

    /** 版本号，同一逻辑记忆递增。 */
    private Integer version;

    /** 被本条替代的旧记忆 ID。 */
    private String supersedesId;

    /** 生效起始时间。 */
    private Instant validFrom;

    /** 生效结束时间。 */
    private Instant validUntil;

    /** 过期时间，过期记忆不再参与上下文。 */
    private Instant expiresAt;

    /** 最近一次被上下文使用的时间。 */
    private Instant lastUsedAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间，由应用层赋值。 */
    private Instant updatedAt;

    /** 逻辑删除时间。 */
    private Instant deletedAt;

    /**
     * 全文检索相关度分数，来自 {@code MATCH(content, summary) AGAINST(...)} 的虚拟列，非表字段。
     *
     * <p>设计文档 11.4 要求相关度分项取该分数按候选集最大值归一化，因此必须把它读出来；
     * 由 MyBatis 自动映射（本列不在实体列清单内，插入与更新不涉及）。非检索语句查出的实体为 {@code null}。
     */
    @TableField(exist = false)
    private Double relevance;
}
