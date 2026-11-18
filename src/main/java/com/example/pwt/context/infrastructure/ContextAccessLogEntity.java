package com.example.pwt.context.infrastructure;

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
 * 上下文访问记录，映射 {@code pt_context_access_log}（设计文档 8.3、11.3 第 9 步）。
 *
 * <p>本表在 14.3 的多租户白名单内：写入需要带上被服务用户的 user_id，而不是取当前主体
 * （Agent 调用时主体是 Agent，数据所有者是 Owner），因此条件与键全部由业务层显式给出。
 *
 * <p>写入走 MyBatis-Plus 自动生成的 INSERT，不使用注解 SQL：JSON 列在注解 SQL 下不继承
 * {@code autoResultMap}，类型处理器会失效（设计文档 8.4 第 17 条，M4 已踩过一次）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_context_access_log", autoResultMap = true)
public class ContextAccessLogEntity {

    /** 记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 被服务的用户，即数据所有者。 */
    private String userId;

    /** 发起请求的 Agent 客户端，Owner 会话为空。 */
    private String agentClientId;

    /** 本次请求的项目，未指定项目为空（V10 迁移新增列）。 */
    private String projectId;

    /** 请求链路 ID，与审计日志同源，便于串联同一次调用。 */
    private String requestId;

    /** 任务描述摘要，入库前截断到列宽。 */
    private String taskSummary;

    /** 本次注入的记忆 ID 列表。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> memoryIds;

    /** 本次注入的 Skill ID 列表，M7 之前恒为空数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> skillIds;

    /** 调用方给出的 Token 预算。 */
    private Integer tokenBudget;

    /** 实际估算的 Token 数。 */
    private Integer tokenEstimate;

    /** 记录时间。 */
    private Instant createdAt;
}
