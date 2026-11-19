package com.example.pwt.context.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作风格规则，映射 {@code pt_style_rule}（设计文档 8.3、11.6 {@code style_rules}）。
 *
 * <p>{@code rule_value} 是 JSON 列：读写都必须走 {@link JacksonTypeHandler}，
 * 且只在 MyBatis-Plus 自动生成的语句（条件构造器）下生效。本模块只用条件构造器，
 * 不写注解 SQL——注解 SQL 不继承 {@code autoResultMap}，JSON 列会被读成 null
 * （设计文档 8.4 第 17 条，M4 已实测踩过）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_style_rule", autoResultMap = true)
public class StyleRuleEntity {

    /** 规则 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 规则键，每用户唯一。 */
    private String ruleKey;

    /** 规则值，JSON 结构由规则键自行约定。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object ruleValue;

    /** 置信度，0 到 1。 */
    private BigDecimal confidence;

    /** 状态：candidate / approved / archived。 */
    private String status;

    /** 观测来源数，达到阈值才允许判定为稳定风格。 */
    private Integer sourceCount;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间；本表由应用层显式赋值，不依赖 ON UPDATE 触发（设计文档 8.4）。 */
    private Instant updatedAt;
}
