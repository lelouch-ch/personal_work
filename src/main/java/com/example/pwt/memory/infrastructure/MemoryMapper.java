package com.example.pwt.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 长期记忆表 Mapper。
 *
 * <p>去重与冲突检测需要“同用户、排除自身、按状态过滤”的组合条件，条件构造器写起来啰嗦且
 * 容易漏掉 {@code deleted_at IS NULL}，因此固定为注解 SQL。
 *
 * <p>本表带 user_id 且不在多租户白名单内，注解 SQL 会自动追加 {@code user_id = ?}；
 * 语句内仍显式写 user_id 条件，两层保险但不互相替代（设计文档 14.3）。
 *
 * @author pwt
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    /**
     * 按规范化摘要查找同类型的既有记忆，用于精确去重（设计文档 6.5）。
     *
     * @param userId          所属用户
     * @param memoryType      记忆类型
     * @param normalizedHash  规范化内容摘要
     * @param excludeId       排除的记忆 ID，可为空
     * @return 命中的既有记忆，最多若干条
     */
    @Select("""
        SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,
               risk_level, visibility, status, source_type, source_id, version, supersedes_id,
               valid_from, valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at
        FROM pt_memory
        WHERE user_id = #{userId} AND memory_type = #{memoryType}
          AND normalized_hash = #{normalizedHash} AND deleted_at IS NULL
          AND status IN ('candidate', 'approved')
          AND (#{excludeId,jdbcType=VARCHAR} IS NULL OR id <> #{excludeId,jdbcType=VARCHAR})
        ORDER BY created_at DESC
        LIMIT 5
        """)
    List<MemoryEntity> selectDuplicates(@Param("userId") String userId, @Param("memoryType") String memoryType,
                                        @Param("normalizedHash") String normalizedHash,
                                        @Param("excludeId") String excludeId);

    /**
     * 统计同用户、同类型、同规范化内容的既有记忆条数，用于判断是否为重复证据。
     *
     * @param userId         所属用户
     * @param memoryType     记忆类型
     * @param normalizedHash 规范化内容摘要
     * @return 记忆条数
     */
    @Select("SELECT COUNT(*) FROM pt_memory WHERE user_id = #{userId} AND memory_type = #{memoryType}"
        + " AND normalized_hash = #{normalizedHash} AND deleted_at IS NULL"
        + " AND status IN ('candidate', 'approved')")
    long countByNormalizedHash(@Param("userId") String userId, @Param("memoryType") String memoryType,
                               @Param("normalizedHash") String normalizedHash);

    /**
     * 按关键词近似召回既有记忆，供冲突检测使用（设计文档 6.5 近似去重）。
     *
     * <p>用自然语言模式：冲突检测的输入来自候选记忆正文，属于不可控文本，
     * 布尔模式的运算符会静默改变召回（8.4 第 15 条）。
     *
     * @param userId     所属用户
     * @param memoryType 记忆类型，为空表示不限
     * @param phrase     检索词
     * @param excludeId  排除的记忆 ID，可为空
     * @param limit      返回上限
     * @return 候选记忆，按相关度倒序
     */
    @Select("""
        <script>
        SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,
               risk_level, visibility, status, source_type, source_id, version, supersedes_id,
               valid_from, valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at,
               MATCH(content, summary) AGAINST(#{phrase} IN NATURAL LANGUAGE MODE) AS relevance
        FROM pt_memory
        WHERE user_id = #{userId} AND deleted_at IS NULL
          AND status IN ('candidate', 'approved')
          AND MATCH(content, summary) AGAINST(#{phrase} IN NATURAL LANGUAGE MODE)
        <if test="memoryType != null">AND memory_type = #{memoryType}</if>
        <if test="excludeId != null">AND id &lt;&gt; #{excludeId}</if>
        ORDER BY relevance DESC, created_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<MemoryEntity> selectSimilar(@Param("userId") String userId, @Param("memoryType") String memoryType,
                                     @Param("phrase") String phrase, @Param("excludeId") String excludeId,
                                     @Param("limit") int limit);

    /**
     * 锁定记忆行，用于审核状态迁移（设计文档 10.4 第 1 步）。
     *
     * @param id 记忆 ID
     * @return 记忆，不存在时为 {@code null}
     */
    @Select("""
        SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,
               risk_level, visibility, status, source_type, source_id, version, supersedes_id,
               valid_from, valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at
        FROM pt_memory WHERE id = #{id} FOR UPDATE
        """)
    MemoryEntity selectForUpdate(@Param("id") String id);

    /**
     * 查找同一逻辑记忆链上仍处于 approved 的旧版本，用于替代（设计文档 6.5 版本策略）。
     *
     * @param userId     所属用户
     * @param memoryType 记忆类型
     * @param excludeId  新记忆自身 ID
     * @param limit      返回上限
     * @return 旧版本记忆
     */
    @Select("SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,"
        + " risk_level, visibility, status, source_type, source_id, version, supersedes_id, valid_from,"
        + " valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at FROM pt_memory"
        + " WHERE user_id = #{userId} AND memory_type = #{memoryType} AND status = 'approved'"
        + " AND deleted_at IS NULL AND id <> #{excludeId} ORDER BY created_at DESC LIMIT #{limit}")
    List<MemoryEntity> selectApprovedByType(@Param("userId") String userId, @Param("memoryType") String memoryType,
                                            @Param("excludeId") String excludeId, @Param("limit") int limit);
}
