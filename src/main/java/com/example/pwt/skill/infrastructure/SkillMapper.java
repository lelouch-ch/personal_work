package com.example.pwt.skill.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * Skill 表 Mapper。
 *
 * <p>检索语句固定在这里而不用条件构造器：全文检索与 {@code LIKE} 兜底都要显式控制列清单与
 * 排序（设计文档 11.3 第 3 步），做法与 {@code MemoryMapper} 一致。
 *
 * <p>本表带 {@code user_id} 且不在多租户白名单内，注解 SQL 会自动追加 {@code user_id = ?}；
 * 语句内仍显式写 user_id 条件，两层保险但不互相替代（设计文档 14.3）。
 *
 * <p>JSON 列 {@code source_memory_ids} 在注解 SQL 下必须显式绑定 {@link JacksonTypeHandler}，
 * 否则读出来是 {@code null} 且不报错（设计文档 8.4 第 17 条，M3/M4 各踩过一次）。
 *
 * @author pwt
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /** 检索语句的结果映射名，兜底语句复用它以免两处漏配 JSON 处理器。 */
    String SEARCH_RESULTS = "skillSearchResults";

    /**
     * 按已组装的检索串做布尔模式全文召回，只取已发布的 Skill。
     *
     * <p>用布尔模式的前提是入参已由 {@code ContextQuery} 剔除运算符字符，
     * 不接受调用方直传的原始文本（8.4 第 15 条）。
     *
     * @param userId       所属用户
     * @param booleanQuery 组装后的布尔模式查询串，调用方保证非空
     * @param ids          Skill ID 白名单，为空表示不限（9.4 get_skills 的 skill_ids）
     * @param limit        返回上限
     * @return 命中 Skill，按相关度倒序
     */
    @Results(id = SEARCH_RESULTS, value = {
        @Result(column = "source_memory_ids", property = "sourceMemoryIds",
            typeHandler = JacksonTypeHandler.class)
    })
    @Select("""
        <script>
        SELECT id, user_id, name, slug, description, content_md, version, status, visibility,
               source_memory_ids, created_at, updated_at,
               MATCH(name, description, content_md) AGAINST(#{booleanQuery} IN BOOLEAN MODE) AS relevance
        FROM pt_skill
        WHERE user_id = #{userId} AND status = 'published'
          AND MATCH(name, description, content_md) AGAINST(#{booleanQuery} IN BOOLEAN MODE) &gt; 0
        <if test="ids != null and ids.size() > 0">
          AND id IN
          <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
        </if>
        ORDER BY relevance DESC, updated_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<SkillEntity> searchPublished(@Param("userId") String userId,
                                      @Param("booleanQuery") String booleanQuery,
                                      @Param("ids") List<String> ids,
                                      @Param("limit") int limit);

    /**
     * {@code LIKE} 兜底召回：全文检索空转时使用（8.5 单字退化、11.3 第 3 步）。
     *
     * <p>{@code ngram_token_size = 2} 下单字词永远命中不了索引，布尔检索静默返回 0 行，
     * 与「确实没有相关 Skill」不可区分，因此补一次子串匹配。代价是 {@code %词%} 用不上索引，
     * 范围由 {@code idx_skill_user_status} 先按 {@code user_id + status} 收敛。
     *
     * @param userId       所属用户
     * @param likePatterns 已加通配符并转义的 LIKE 模式，调用方保证非空
     * @param ids          Skill ID 白名单，为空表示不限（9.4 get_skills 的 skill_ids）
     * @param limit        返回上限
     * @return 命中 Skill，按更新时间倒序
     */
    @ResultMap(SEARCH_RESULTS)
    @Select("""
        <script>
        SELECT id, user_id, name, slug, description, content_md, version, status, visibility,
               source_memory_ids, created_at, updated_at
        FROM pt_skill
        WHERE user_id = #{userId} AND status = 'published'
          AND (
          <foreach collection="likePatterns" item="pattern" separator=" OR ">
            name LIKE #{pattern} ESCAPE '\\\\' OR description LIKE #{pattern} ESCAPE '\\\\'
            OR content_md LIKE #{pattern} ESCAPE '\\\\'
          </foreach>
          )
        <if test="ids != null and ids.size() > 0">
          AND id IN
          <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
        </if>
        ORDER BY updated_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<SkillEntity> searchPublishedByLike(@Param("userId") String userId,
                                            @Param("likePatterns") List<String> likePatterns,
                                            @Param("ids") List<String> ids,
                                            @Param("limit") int limit);

    /**
     * 取同一 slug 已用过的最大版本号，用于分配新版本。
     *
     * <p>不过滤状态：草稿、已发布、已归档都占着 {@code uq_skill_user_slug_version} 的位置，
     * 从最大值往后分配才能避免唯一键冲突。
     *
     * @param userId 所属用户
     * @param slug   稳定标识
     * @return 最大版本号，该 slug 不存在时为 0
     */
    @Select("SELECT COALESCE(MAX(version), 0) FROM pt_skill WHERE user_id = #{userId} AND slug = #{slug}")
    int selectMaxVersion(@Param("userId") String userId, @Param("slug") String slug);
}