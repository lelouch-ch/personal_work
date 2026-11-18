package com.example.pwt.context.application;

import com.example.pwt.common.api.Scope;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.context.domain.ContextWeights;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.application.MemoryRecall;
import com.example.pwt.memory.application.MemoryRecallService;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code search_memories}：按关键词检索已批准记忆（设计文档 9.4、9.6、11.3）。
 *
 * <p>与 {@link ContextBuilderService} 分开：搜索返回“有哪些相关记忆”，上下文构建返回
 * “这次任务该带上什么”。前者要能翻页、要给出命中路径，后者受预算约束；
 * 合成一个接口会导致搜索也被预算截断，Agent 无法回答“我一共记了多少条”。
 *
 * @author pwt
 */
@Service
public class ContextSearchService {

    /** 查询长度上限，与 9.4 {@code search_memories} 的 Schema 一致。 */
    private static final int MAX_QUERY_LENGTH = 1000;

    /** 单次返回上限，与 9.4 Schema 一致。 */
    private static final int MAX_LIMIT = 50;

    /** 搜索结果里注入文本的长度上限。 */
    private static final int MAX_TEXT_CHARS = 1000;

    private final MemoryRecallService memoryRecallService;

    private final MemoryEvidenceMapper evidenceMapper;

    /**
     * 构造服务。
     *
     * @param memoryRecallService 记忆召回
     * @param evidenceMapper      证据 Mapper，用于补出来源引用
     */
    public ContextSearchService(MemoryRecallService memoryRecallService, MemoryEvidenceMapper evidenceMapper) {
        this.memoryRecallService = memoryRecallService;
        this.evidenceMapper = evidenceMapper;
    }

    /**
     * 检索记忆。
     *
     * @param actor     当前主体，Agent 需 {@code memory:read}
     * @param query     关键词或自然语言短语
     * @param types     类型过滤，可为空
     * @param projectId 项目过滤，可为空
     * @param limit     返回上限，1~50
     * @return 命中结果，含召回路径
     * @throws BizException 查询为空、类型非法或上限越界
     */
    public SearchResult search(ActorContext actor, String query, List<String> types, UUID projectId,
                              Integer limit) {
        actor.requireScope(Scope.MEMORY_READ);
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "查询词不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "查询词长度不能超过 " + MAX_QUERY_LENGTH);
        }
        int capped = limit == null ? 10 : limit;
        if (capped < 1 || capped > MAX_LIMIT) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "limit 需在 1 到 " + MAX_LIMIT + " 之间");
        }
        List<MemoryType> parsed = parseTypes(types);

        MemoryRecall recall = memoryRecallService.recallByKeyword(actor, query, parsed,
            projectId == null ? null : projectId.toString(), capped);
        List<MemoryEntity> rows = recall.candidates();
        Map<UUID, List<UUID>> refs = sourceRefs(rows);
        double maxRelevance = rows.stream()
            .map(MemoryEntity::getRelevance)
            .filter(java.util.Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0.0D);
        Instant now = Instant.now();
        List<Hit> hits = new ArrayList<>(rows.size());
        for (MemoryEntity row : rows) {
            UUID id = UUID.fromString(row.getId());
            double relevance = recall.fullText() && maxRelevance > 0.0D && row.getRelevance() != null
                ? row.getRelevance() / maxRelevance
                : ContextWeights.likeFallbackRelevance();
            double score = ContextWeights.score(new ContextWeights.Parts(
                ContextWeights.relevance(relevance),
                ContextWeights.importance(row.getImportance()),
                ContextWeights.confidence(row.getConfidence()),
                ContextWeights.recency(row.getCreatedAt(), now),
                ContextWeights.projectMatch(row.getProjectId(),
                    projectId == null ? null : projectId.toString()),
                ContextWeights.usageSignal(row.getLastUsedAt(), now)));
            hits.add(new Hit(id, row.getMemoryType(), truncate(row.getSummary() == null
                ? row.getContent() : row.getSummary()), row.getConfidence(),
                round(score), refs.getOrDefault(id, List.of()), row.getRiskLevel(),
                row.getProjectId() == null ? null : UUID.fromString(row.getProjectId()),
                row.getCreatedAt()));
        }
        return new SearchResult(hits, recall.fullText() ? ContextPacket.Meta.PATH_FULL_TEXT
            : ContextPacket.Meta.PATH_LIKE);
    }

    /**
     * 一次检索的结果。
     *
     * @param hits       命中列表，已按分数倒序（由召回顺序保证）
     * @param recallPath 召回路径，供调用方判断结果是否为兜底所得
     */
    public record SearchResult(List<Hit> hits, String recallPath) {
    }

    /**
     * 一条命中。
     *
     * @param id         记忆 ID
     * @param type       记忆类型
     * @param text       摘要，缺摘要时为正文截断
     * @param confidence 置信度
     * @param score      加权总分
     * @param sourceRefs 来源工作记录
     * @param riskLevel  风险等级
     * @param projectId  归属项目
     * @param createdAt  创建时间
     */
    public record Hit(UUID id, String type, String text, java.math.BigDecimal confidence, double score,
                      List<UUID> sourceRefs, String riskLevel, UUID projectId, Instant createdAt) {
    }

    private List<MemoryType> parseTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        List<MemoryType> parsed = new ArrayList<>(types.size());
        for (String value : types) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(MemoryType.fromValue(value.strip()));
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆类型非法：" + value, e);
            }
        }
        return parsed;
    }

    private Map<UUID, List<UUID>> sourceRefs(List<MemoryEntity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> ids = rows.stream().map(MemoryEntity::getId).toList();
        Map<UUID, List<UUID>> refs = new LinkedHashMap<>();
        for (var evidence : evidenceMapper.selectWorkLogRefs(ids)) {
            if (evidence.getMemoryId() == null || evidence.getWorkLogId() == null) {
                continue;
            }
            refs.computeIfAbsent(UUID.fromString(evidence.getMemoryId()), key -> new ArrayList<>())
                .add(UUID.fromString(evidence.getWorkLogId()));
        }
        return refs;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_TEXT_CHARS ? stripped : stripped.substring(0, MAX_TEXT_CHARS) + "…";
    }

    private double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }
}
