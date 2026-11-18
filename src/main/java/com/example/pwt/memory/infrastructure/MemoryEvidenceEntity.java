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
 * 记忆证据持久化对象，映射 {@code pt_memory_evidence}（设计文档 6.6）。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_memory_evidence", autoResultMap = true)
public class MemoryEvidenceEntity {

    /** 证据 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 关联记忆。 */
    private String memoryId;

    /** 来源工作记录，可为空。 */
    private String workLogId;

    /** 证据类型。 */
    private String evidenceType;

    /** 原文摘录。 */
    private String excerpt;

    /** 来源元数据。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceMetadata;

    /** 创建时间。 */
    private Instant createdAt;
}
