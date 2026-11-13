package com.example.pwt.work.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 项目持久化对象，映射 {@code pt_project}。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_project", autoResultMap = true)
public class ProjectEntity {

    /** 项目 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 项目名称。 */
    private String name;

    /** 项目说明。 */
    private String description;

    /** 状态：active / paused / completed / archived。 */
    private String status;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 开始日期。 */
    private LocalDate startedAt;

    /** 结束日期。 */
    private LocalDate endedAt;

    /** 扩展元数据。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /** 创建时间，UTC。 */
    private Instant createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护。 */
    private Instant updatedAt;

    /** 软删除时间，NULL 表示未删除。 */
    private Instant deletedAt;
}
