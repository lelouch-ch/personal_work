package com.example.pwt.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
               risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id,
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
               risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id,
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
               risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id,
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
        + " risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id, valid_from,"
        + " valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at FROM pt_memory"
        + " WHERE user_id = #{userId} AND memory_type = #{memoryType} AND status = 'approved'"
        + " AND deleted_at IS NULL AND id <> #{excludeId} ORDER BY created_at DESC LIMIT #{limit}")
    List<MemoryEntity> selectApprovedByType(@Param("userId") String userId, @Param("memoryType") String memoryType,
                                            @Param("excludeId") String excludeId, @Param("limit") int limit);

    /**
     * 布尔模式关键词召回，供 {@code search_memories} 与 Context Builder 使用（设计文档 11.3 第 3 步）。
     *
     * <p>这里用布尔模式而不是自然语言模式：检索串由 Context Builder 按 {@code ngram_token_size}
     * 自行组装，运算符已在组装前剔除，符合 8.4 第 15 条对两种模式的分工约定。
     * 长任务描述拆成 2-gram 后按 OR 取并集，命中词越多分数越高，天然形成相关度梯度；
     * 自然语言模式按整串计算权重，长句会被自身的常见字稀释。
     *
     * <p>{@code relevance} 是虚拟列，值为该行的 {@code MATCH} 分数，由调用方按候选集最大值归一化
     * （11.4），映射到 {@link MemoryEntity#getRelevance()}。
     *
     * <p>状态、有效期与项目过滤下推到 SQL；可见性判定留在 Java 层：它是跨模块复用的权限规则，
     * 不适合复制进每条检索语句（见 {@link com.example.pwt.identity.domain.AccessPolicy}）。
     *
     * @param booleanQuery 已组装的布尔模式检索串，调用方保证非空
     * @param types        记忆类型存储值，为空表示不限
     * @param projectId    项目过滤，可为空
     * @param now          有效期判定基准时刻
     * @param limit        返回上限
     * @return 候选记忆，按相关度倒序
     */
    @Select("""
        <script>
        SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,
               risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id,
               valid_from, valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at,
               MATCH(content, summary) AGAINST(#{booleanQuery} IN BOOLEAN MODE) AS relevance
        FROM pt_memory
        WHERE status = 'approved' AND deleted_at IS NULL
          AND (expires_at IS NULL OR expires_at &gt; #{now})
          AND (valid_from IS NULL OR valid_from &lt;= #{now})
          AND (valid_until IS NULL OR valid_until &gt;= #{now})
          AND MATCH(content, summary) AGAINST(#{booleanQuery} IN BOOLEAN MODE) &gt; 0
        <if test="types != null and types.size() > 0">
          AND memory_type IN
          <foreach collection="types" item="type" open="(" separator="," close=")">#{type}</foreach>
        </if>
        <if test="projectId != null">
          AND (project_id = #{projectId} OR project_id IS NULL)
        </if>
        ORDER BY relevance DESC, created_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<MemoryEntity> searchBoolean(@Param("booleanQuery") String booleanQuery,
                                     @Param("types") List<String> types,
                                     @Param("projectId") String projectId,
                                     @Param("now") Instant now,
                                     @Param("limit") int limit);

    /**
     * {@code LIKE} 兜底召回：全文检索空转时使用（设计文档 8.5 的单字退化、11.3 第 3 步）。
     *
     * <p>{@code ngram_token_size = 2} 下单字词永远命中不了索引，布尔检索会静默返回 0 行，
     * 与“确实没有相关记忆”不可区分，因此必须保留子串匹配兜底。
     *
     * <p>代价是 {@code %词%} 用不上索引：范围由 {@code idx_memory_user_status_created}
     * 先按 {@code user_id + status} 收敛到已批准且在有效期内的行，个人库该子集规模可控。
     * 命中行由调用方记为固定低相关度（11.4）。
     *
     * @param likePatterns 已加通配符并转义的 LIKE 模式，调用方保证非空
     * @param types        记忆类型存储值，为空表示不限
     * @param projectId    项目过滤，可为空
     * @param now          有效期判定基准时刻
     * @param limit        返回上限
     * @return 候选记忆，按创建时间倒序
     */
    @Select("""
        <script>
        SELECT id, user_id, memory_type, content, summary, normalized_hash, confidence, importance,
               risk_level, visibility, status, source_type, source_id, project_id, version, supersedes_id,
               valid_from, valid_until, expires_at, last_used_at, created_at, updated_at, deleted_at
        FROM pt_memory
        WHERE status = 'approved' AND deleted_at IS NULL
          AND (expires_at IS NULL OR expires_at &gt; #{now})
          AND (valid_from IS NULL OR valid_from &lt;= #{now})
          AND (valid_until IS NULL OR valid_until &gt;= #{now})
          AND (
          <foreach collection="likePatterns" item="pattern" separator=" OR ">
            content LIKE #{pattern} ESCAPE '\\\\' OR summary LIKE #{pattern} ESCAPE '\\\\'
          </foreach>
          )
        <if test="types != null and types.size() > 0">
          AND memory_type IN
          <foreach collection="types" item="type" open="(" separator="," close=")">#{type}</foreach>
        </if>
        <if test="projectId != null">
          AND (project_id = #{projectId} OR project_id IS NULL)
        </if>
        ORDER BY created_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<MemoryEntity> searchByLike(@Param("likePatterns") List<String> likePatterns,
                                    @Param("types") List<String> types,
                                    @Param("projectId") String projectId,
                                    @Param("now") Instant now,
                                    @Param("limit") int limit);

    /**
     * 批量回写使用信号：把被上下文选中的记忆标记为刚使用过（设计文档 11.4）。
     *
     * <p>单条 UPDATE 而不是逐行 {@code updateById}：一次 {@code build_context} 可能命中数十条记忆，
     * 逐行写会放大行锁与 binlog 开销，这正是 11.4 明确禁止主事务逐行写的原因。
     *
     * <p>调用方须在业务事务之外执行：使用信号是尽力而为的统计，失败只影响下次排序，
     * 不该把整次上下文构建带回滚。
     *
     * @param memoryIds 记忆 ID 集合，调用方保证非空
     * @param usedAt    使用时间
     * @return 更新行数
     */
    @Update("""
        <script>
        UPDATE pt_memory SET last_used_at = #{usedAt}
        WHERE id IN
        <foreach collection="memoryIds" item="memoryId" open="(" separator="," close=")">#{memoryId}</foreach>
        </script>
        """)
    int touchLastUsed(@Param("memoryIds") List<String> memoryIds, @Param("usedAt") Instant usedAt);
}
