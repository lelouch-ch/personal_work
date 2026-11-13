package com.example.pwt.work.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作记录持久化对象，映射 {@code pt_work_log}。
 *
 * <p>{@code idempotencyScope} 是 STORED 生成列（user_id + idempotency_key），
 * 由数据库计算，不参与写入；实体不声明该字段。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_work_log", autoResultMap = true)
public class WorkLogEntity {

    /** 记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 关联项目，可为空。 */
    private String projectId;

    /** 提交方 Agent 客户端 ID；手动录入为空。 */
    private String agentClientId;

    /** 来源：manual / agent / import。 */
    private String sourceType;

    /** 标题。 */
    private String title;

    /** 摘要。 */
    private String summary;

    /** 正文，保留原始内容。 */
    private String content;

    /** 工作类型。 */
    private String workType;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 状态：active / archived / deleted。 */
    private String status;

    /** 事件发生时间，UTC。 */
    private Instant occurredAt;

    /** 标签列表，存储为 JSON 数组，入库前统一小写归一。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /** 幂等键，可为空。 */
    private String idempotencyKey;

    /** 创建时间，UTC。 */
    private Instant createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护。 */
    private Instant updatedAt;
}
