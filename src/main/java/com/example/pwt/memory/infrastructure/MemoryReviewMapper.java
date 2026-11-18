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
 * 记忆审核留痕表 Mapper。
 *
 * <p>{@code rule_snapshot} 是 JSON 列，处理方式同 {@link MemoryEvidenceMapper}：
 * 注解 SQL 不继承实体的 {@code autoResultMap}，需显式声明类型处理器。
 *
 * @author pwt
 */
@Mapper
public interface MemoryReviewMapper extends BaseMapper<MemoryReviewEntity> {

    /**
     * 查询某条记忆的审核历史。
     *
     * @param memoryId 记忆 ID
     * @return 审核记录，按时间倒序
     */
    @Results(id = "memoryReviewResults", value = {
        @Result(column = "rule_snapshot", property = "ruleSnapshot",
            typeHandler = JacksonTypeHandler.class)
    })
    @Select("SELECT id, user_id, memory_id, review_type, risk_level, decision, reason, reviewer_type,"
        + " reviewer_id, rule_snapshot, created_at FROM pt_memory_review WHERE memory_id = #{memoryId}"
        + " ORDER BY created_at DESC")
    List<MemoryReviewEntity> selectByMemory(@Param("memoryId") String memoryId);
}
