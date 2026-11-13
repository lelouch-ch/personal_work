package com.example.pwt.work.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工作记录表 Mapper。
 *
 * <p>标签检索与全文检索需要 MySQL 专有语法（{@code MEMBER OF}、{@code MATCH ... AGAINST}），
 * 条件构造器无法表达，因此使用注解 SQL。已实测两种写法都支持参数占位符，全部参数绑定，
 * 不做字符串拼接。
 *
 * <p>本表带 user_id 列且不在多租户白名单内，注解 SQL 会被 {@code TenantLineInnerInterceptor}
 * 自动追加 {@code user_id = ?}；调用方仍需自行按可见性过滤。
 *
 * @author pwt
 */
@Mapper
public interface WorkLogMapper extends BaseMapper<WorkLogEntity> {

    /**
     * 按标签检索活跃记录。
     *
     * <p>多值索引只在 {@code MEMBER OF} 或 {@code JSON_CONTAINS} 下命中，
     * {@code JSON_UNQUOTE(JSON_EXTRACT(...))} 会退化为全表扫描（设计文档 8.4 第 9 条）。
     *
     * @param tag   标签，须已在写入端归一为小写
     * @param since 起始发生时间，为空表示不限
     * @param limit 返回条数上限
     * @return 命中的记录，按发生时间倒序
     */
    @Select("""
        <script>
        SELECT id, user_id, project_id, agent_client_id, source_type, title, summary, content,
               work_type, visibility, status, occurred_at, tags, idempotency_key,
               created_at, updated_at
        FROM pt_work_log
        WHERE status = 'active'
          AND #{tag} MEMBER OF (tags)
        <if test="since != null">AND occurred_at &gt;= #{since}</if>
        ORDER BY occurred_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<WorkLogEntity> selectByTag(@Param("tag") String tag, @Param("since") Instant since,
                                    @Param("limit") int limit);

    /**
     * 关键词全文检索，按 ngram 相关度倒序。
     *
     * <p>使用自然语言模式：调用方传入的是未经清洗的用户输入，而布尔模式下输入自带的
     * {@code + - ( ")} 等运算符会改变检索语义，实测 {@code "(上传"} 这类输入静默返回 0 行。
     * 设计文档 11.3 的布尔模式留给 Context Builder，由它自行组装受控检索串。
     *
     * <p>{@code ngram_token_size = 2} 下单字词无法命中，调用方需按设计文档 11.3 走
     * {@code LIKE} 兜底；相关度分数由 {@code MATCH} 直接给出。
     *
     * @param phrase 关键词，按自然语言模式解析
     * @param since  起始发生时间，为空表示不限
     * @param limit  返回条数上限
     * @return 命中的记录，按相关度倒序
     */
    @Select("""
        <script>
        SELECT id, user_id, project_id, agent_client_id, source_type, title, summary, content,
               work_type, visibility, status, occurred_at, tags, idempotency_key,
               created_at, updated_at,
               MATCH(title, summary, content) AGAINST(#{phrase} IN NATURAL LANGUAGE MODE) AS relevance
        FROM pt_work_log
        WHERE status = 'active'
          AND MATCH(title, summary, content) AGAINST(#{phrase} IN NATURAL LANGUAGE MODE)
        <if test="since != null">AND occurred_at &gt;= #{since}</if>
        ORDER BY relevance DESC, occurred_at DESC
        LIMIT #{limit}
        </script>
        """)
    List<WorkLogEntity> searchFullText(@Param("phrase") String phrase, @Param("since") Instant since,
                                       @Param("limit") int limit);
}
