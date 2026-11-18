package com.example.pwt.memory.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 记忆审核留痕持久化对象，映射 {@code pt_memory_review}（设计文档 6.4、10.4）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_memory_review", autoResultMap = true)
public class MemoryReviewEntity {

    /** 审核记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 被审核的记忆。 */
    private String memoryId;

    /** 审核类型：AUTO / OWNER 等。 */
    private String reviewType;

    /** 审核时的风险等级。 */
    private String riskLevel;

    /** 决策，取值见 {@link com.example.pwt.memory.domain.ReviewDecision}。 */
    private String decision;

    /** 决策原因。 */
    private String reason;

    /** 审核人类型：system / owner / admin。 */
    private String reviewerType;

    /** 审核人 ID。 */
    private String reviewerId;

    /** 判定时的规则与输入快照，便于复盘。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> ruleSnapshot;

    /** 创建时间。 */
    private Instant createdAt;
}
