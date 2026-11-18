package com.example.pwt.memory.application;

import com.example.pwt.context.domain.ContextQuery;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆召回：全文检索优先，空结果时用 {@code LIKE} 兜底（设计文档 11.3 第 3 步、8.5 分词限制）。
 *
 * <p>兜底判定只看“全文检索是否返回 0 行”：{@code ngram_token_size = 2} 下单字查询与
 * 整串被切成停用组合的查询都会空转，而空转与“确实没有相关记忆”在结果上不可区分，
 * 只能补一次子串匹配。补一次而不是每次都做，是为了不让索引路径白付一遍扫描成本。
 *
 * <p>有效期、状态与项目条件下推到 SQL，可见性判定留在 Java：后者是跨模块的权限规则
 * （{@link AccessPolicy}），复制进 SQL 会与 M4 之前的行为漂移。
 *
 * @author pwt
 */
@Service
public class MemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallService.class);

    private final MemoryMapper memoryMapper;

    /**
     * 构造服务。
     *
     * @param memoryMapper 记忆 Mapper
     */
    public MemoryRecallService(MemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    /**
     * 按检索串召回可注入上下文的记忆。
     *
     * @param actor      当前主体，决定可见性档位
     * @param query      已组装的检索串
     * @param types      记忆类型白名单，为空表示不限
     * @param projectId  项目过滤，可为空
     * @param limit      返回上限
     * @return 召回结果，含命中路径标记
     */
    @Transactional(readOnly = true)
    public MemoryRecall recall(ActorContext actor, ContextQuery query, Collection<MemoryType> types,
                               String projectId, int limit) {
        if (actor == null || limit <= 0) {
            return MemoryRecall.empty();
        }
        Instant now = Instant.now();
        List<String> typeValues = toTypeValues(types);
        List<MemoryEntity> rows = List.of();
        if (query.usableForFullText()) {
            rows = readable(actor, memoryMapper.searchBoolean(query.booleanQuery(), typeValues, projectId,
                now, limit));
        }
        if (!rows.isEmpty()) {
            return new MemoryRecall(rows, true);
        }
        List<String> patterns = likePatterns(query);
        if (patterns.isEmpty()) {
            return MemoryRecall.empty();
        }
        // 走兜底路径：多为单字查询或 ngram 未命中的长串，记 INFO 便于线上观察兜底频率
        log.info("全文检索空转, 使用 LIKE 兜底, terms={}", query.likeTerms());
        rows = readable(actor, memoryMapper.searchByLike(patterns, typeValues, projectId, now, limit));
        return new MemoryRecall(rows, false);
    }

    /**
     * 按关键词召回，供 {@code search_memories} 使用。
     *
     * <p>关键词同样先组装成受控检索串再走布尔模式：本方法的调用方是 MCP 工具与 REST 接口，
     * 输入不可控，直接送进布尔模式会因输入自带的运算符改变语义（8.4 第 15 条）。
     *
     * @param actor     当前主体
     * @param keyword   关键词或自然语言短语
     * @param types     记忆类型白名单，为空表示不限
     * @param projectId 项目过滤，可为空
     * @param limit     返回上限
     * @return 召回结果
     */
    @Transactional(readOnly = true)
    public MemoryRecall recallByKeyword(ActorContext actor, String keyword, Collection<MemoryType> types,
                                        String projectId, int limit) {
        return recall(actor, ContextQuery.parse(keyword), types, projectId, limit);
    }

    private List<MemoryEntity> readable(ActorContext actor, List<MemoryEntity> rows) {
        return rows.stream().filter(row -> AccessPolicy.readableBy(actor, row.getVisibility())).toList();
    }

    private List<String> toTypeValues(Collection<MemoryType> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (MemoryType type : types) {
            if (type != null) {
                values.add(type.getValue());
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> likePatterns(ContextQuery query) {
        return query.likeTerms().stream()
            .map(term -> "%" + ContextQuery.escapeLike(term) + "%")
            .toList();
    }
}
