package com.example.pwt.task.infrastructure;

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
 * 异步任务持久化对象，映射 {@code pt_async_task}。
 *
 * <p>{@code dedupKey} 是 STORED 生成列（user_id + task_type + dedup_ref），
 * 由数据库计算，不参与写入；实体不声明该字段（设计文档 8.4 第 8 条、ADR-021）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_async_task", autoResultMap = true)
public class AsyncTaskEntity {

    /** 任务 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户，同时作为多租户隔离键。 */
    private String userId;

    /** 任务类型，取值见 {@link com.example.pwt.task.domain.TaskType}。 */
    private String taskType;

    /** 去重引用的业务对象 ID，为空表示该类任务不去重。 */
    private String dedupRef;

    /** 任务入参。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    /** 状态：pending / running / succeeded / failed / cancelled。 */
    private String status;

    /** 已重试次数。 */
    private Integer retryCount;

    /** 最大重试次数。 */
    private Integer maxRetries;

    /** 下次可被领取的时间，UTC。 */
    private Instant nextRetryAt;

    /** 领取时间，用于租约回收，UTC。 */
    private Instant lockedAt;

    /** 领取者标识。 */
    private String lockedBy;

    /** 最近一次失败的脱敏错误信息。 */
    private String lastError;

    /** 创建时间，UTC。 */
    private Instant createdAt;

    /** 更新时间，由应用层赋值。 */
    private Instant updatedAt;
}
