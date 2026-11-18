package com.example.pwt.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Results;

/**
 * 记忆证据表 Mapper。
 *
 * <p>{@code source_metadata} 是 JSON 列，必须显式声明类型处理器：注解 SQL 不继承实体上的
 * {@code autoResultMap}，否则提取模型与提示词版本等留痕会被读成 null（设计文档 6.6、12.3）。
 *
 * @author pwt
 */
@Mapper
public interface MemoryEvidenceMapper extends BaseMapper<MemoryEvidenceEntity> {

    /**
     * 查询某条记忆的全部证据，供审核界面展示（设计文档 16.3）。
     *
     * @param memoryId 记忆 ID
     * @return 证据列表
     */
    @Results(id = "memoryEvidenceResults", value = {
        @Result(column = "source_metadata", property = "sourceMetadata",
            typeHandler = JacksonTypeHandler.class)
    })
    @Select("SELECT id, user_id, memory_id, work_log_id, evidence_type, excerpt, source_metadata, created_at"
        + " FROM pt_memory_evidence WHERE memory_id = #{memoryId} ORDER BY created_at")
    List<MemoryEvidenceEntity> selectByMemory(@Param("memoryId") String memoryId);

    /**
     * 统计某条记忆的证据条数，用于自动批准规则的“重复证据数”判定（设计文档 6.3 第 3 条）。
     *
     * @param memoryId 记忆 ID
     * @return 证据条数
     */
    @Select("SELECT COUNT(*) FROM pt_memory_evidence WHERE memory_id = #{memoryId}")
    long countByMemory(@Param("memoryId") String memoryId);
}
