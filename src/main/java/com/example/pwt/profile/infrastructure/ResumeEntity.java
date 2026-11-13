package com.example.pwt.profile.infrastructure;

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
 * 简历持久化对象，映射 {@code pt_resume}。
 *
 * <p>{@code currentUserId} 是 STORED 生成列，替代 PostgreSQL 的部分唯一索引，
 * 保证每个用户最多一份当前简历；该列由数据库计算，不参与写入（设计文档 8.4 第 8 条）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_resume", autoResultMap = true)
public class ResumeEntity {

    /** 简历 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 版本号，与 user_id 组成唯一键。 */
    private Integer version;

    /** 简历标题。 */
    private String title;

    /** Markdown 正文。 */
    private String contentMd;

    /** 结构化简历数据，用于导出与 Agent 消费。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resumeJson;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 是否为当前简历，数据库以生成列强制每用户唯一。 */
    private Boolean isCurrent;

    /** 创建时间，UTC。 */
    private Instant createdAt;
}
