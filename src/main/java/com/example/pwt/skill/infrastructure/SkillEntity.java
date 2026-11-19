package com.example.pwt.skill.infrastructure;

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
 * Skill，映射 {@code pt_skill}（设计文档 5.2、8.3）。
 *
 * <p>Skill 是「可执行工作方法」，与长期记忆分开：记忆是事实与经验，Skill 是
 * Agent 能按步骤使用的流程、模板或规则，内容由 Owner 编写并在发布前自行审核（15.2）。
 *
 * <p>{@code source_memory_ids} 是 JSON 列，读写都必须走 {@link JacksonTypeHandler}，
 * 且只在 MyBatis-Plus 自动生成的语句下生效（设计文档 8.4 第 17 条）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_skill", autoResultMap = true)
public class SkillEntity {

    /** Skill ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 显示名称，允许中文。 */
    private String name;

    /** 稳定标识，Agent 按它引用 Skill；同一用户内按版本唯一。 */
    private String slug;

    /** 一句话用途说明，客户端选型依据。 */
    private String description;

    /** Markdown 正文。 */
    private String contentMd;

    /** 版本号，同一 slug 下单调递增。 */
    private Integer version;

    /** 状态：draft / published / archived。 */
    private String status;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 来源记忆 ID 列表，用于回溯这条 Skill 基于哪些经验写成。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sourceMemoryIds;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;

    /**
     * 全文检索相关度分数，仅检索语句返回，不映射数据库列。
     */
    @TableField(exist = false)
    private Double relevance;
}
