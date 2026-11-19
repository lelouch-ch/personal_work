package com.example.pwt.skill.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.context.domain.ContextQuery;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.skill.domain.SkillStatus;
import com.example.pwt.skill.infrastructure.SkillEntity;
import com.example.pwt.skill.infrastructure.SkillMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 读取：按关键词召回与按 ID 取用（设计文档 9.3、9.6、11.3 第 3 步）。
 *
 * <p>召回沿用 {@code MemoryRecallService} 的做法：检索串由调用方用 {@link ContextQuery}
 * 组装后传入——只有剔除过运算符的自组装串才允许走布尔模式（8.4 第 15 条）；
 * {@code ngram_token_size = 2} 下单字查询在索引上必然空转，空转与「确实没有相关 Skill」
 * 在结果上不可区分，因此全文检索空结果必须补一次子串匹配。
 *
 * <p>只返回 {@code published}（9.6 明确 {@code get_skills} 不含草稿与归档），
 * 可见性判定留在 Java 侧走 {@link AccessPolicy}，不在 SQL 里另写一套档位。
 *
 * @author pwt
 */
@Service
public class SkillRecallService {

    private static final Logger log = LoggerFactory.getLogger(SkillRecallService.class);

    /** 关键词长度上限，与 9.4 {@code get_skills} 的 Schema 一致。 */
    private static final int MAX_QUERY_LENGTH = 500;

    /** {@code skill_ids} 数量上限，与 9.4 Schema 一致。 */
    private static final int MAX_SKILL_IDS = 20;

    /** 缺省返回条数，与 9.4 Schema 的 default 一致。 */
    private static final int DEFAULT_LIMIT = 10;

    /** 返回条数上限，与 {@code search_memories} 同档。 */
    private static final int MAX_LIMIT = 50;

    private final SkillMapper skillMapper;

    /**
     * 构造服务。
     *
     * @param skillMapper Skill Mapper
     */
    public SkillRecallService(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    /**
     * {@code get_skills} 的统一入口（设计文档 9.3、9.4、9.6）。
     *
     * <p>取值校验放在服务层而不是 MCP 层，与 {@code ContextSearchService.search} 同构：
     * REST 与 MCP 两条入口必须得到完全一致的判定结果（9.1）。
     *
     * <p>两种取法按是否给出关键词分流：给出则按相关性召回，未给出则按 ID 白名单或
     * 「最新已发布」取用。同时给出时取交集而不是忽略其一——Agent 说「把我上次用的那几篇里
     * 跟部署相关的挑出来」，忽略任何一个条件都是曲解它的意思。
     *
     * @param actor 当前主体，需 {@code skill:read}
     * @param query 关键词，可为空
     * @param ids   Skill ID 白名单，可为空
     * @param limit 返回条数，可为空表示缺省值
     * @return 可见的已发布 Skill
     * @throws BizException 长度或条数越界
     */
    @Transactional(readOnly = true)
    public List<SkillEntity> getSkills(ActorContext actor, String query, List<UUID> ids, Integer limit) {
        actor.requireScope(Scope.SKILL_READ);
        String trimmed = query == null || query.isBlank() ? null : query.strip();
        if (trimmed != null && trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "查询词长度不能超过 " + MAX_QUERY_LENGTH);
        }
        List<UUID> idList = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
        if (idList.size() > MAX_SKILL_IDS) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "skill_ids 数量不能超过 " + MAX_SKILL_IDS);
        }
        int capped = limit == null ? DEFAULT_LIMIT : limit;
        if (capped < 1 || capped > MAX_LIMIT) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "limit 需在 1 到 " + MAX_LIMIT + " 之间");
        }
        List<String> idValues = idList.stream().map(UUID::toString).toList();
        return trimmed == null ? selectPublished(actor, idValues, capped)
            : recall(actor, ContextQuery.parse(trimmed), idValues, capped).candidates();
    }

    /**
     * 按已组装的检索串召回可见的已发布 Skill，供 Context Builder 注入使用。
     *
     * @param actor 当前主体，需 {@code skill:read}，并决定可见性档位
     * @param query 已组装的检索串，可为空
     * @param ids   ID 白名单，可为空表示不限
     * @param limit 返回上限
     * @return 召回结果，{@code fullText} 标明命中路径
     */
    @Transactional(readOnly = true)
    public SkillRecall recall(ActorContext actor, ContextQuery query, List<String> ids, int limit) {
        actor.requireScope(Scope.SKILL_READ);
        if (limit <= 0) {
            return SkillRecall.empty();
        }
        boolean fullTextUsable = query != null && query.usableForFullText();
        List<String> patterns = query == null ? List.of() : query.likeTerms().stream()
            .map(term -> "%" + ContextQuery.escapeLike(term) + "%")
            .toList();
        if (!fullTextUsable && patterns.isEmpty()) {
            // 组装不出检索词（空查询或全是停用词）：只按 ID 取，不退化成全表扫描
            return new SkillRecall(selectPublished(actor, ids, limit), false);
        }
        List<SkillEntity> rows = List.of();
        if (fullTextUsable) {
            rows = readable(actor,
                skillMapper.searchPublished(actor.userId().toString(), query.booleanQuery(), ids, limit));
        }
        if (!rows.isEmpty()) {
            return new SkillRecall(rows, true);
        }
        if (patterns.isEmpty()) {
            return SkillRecall.empty();
        }
        // 走兜底路径：多为单字查询或 ngram 未命中的长串，记 INFO 便于观察兜底频率
        log.info("Skill 全文检索空转, 使用 LIKE 兜底, terms={}", query.likeTerms());
        rows = readable(actor,
            skillMapper.searchPublishedByLike(actor.userId().toString(), patterns, ids, limit));
        return new SkillRecall(rows, false);
    }

    /**
     * 取已发布且可见的 Skill，不做关键词召回。
     *
     * <p>白名单里不存在、已归档或无权读取的 ID 静默跳过而不是报错：Agent 手里的引用
     * 可能来自上一次会话，那时它已发布、现在可能已被归档，为这种时间差报错会让 Agent
     * 卡在重试上，少拿一条更符合实际语义。
     *
     * @param actor 当前主体
     * @param ids   ID 白名单，为空表示不限
     * @param limit 返回上限
     * @return 已发布且可见的 Skill，按更新时间倒序
     */
    public List<SkillEntity> listPublished(ActorContext actor, List<UUID> ids, int limit) {
        actor.requireScope(Scope.SKILL_READ);
        List<String> values = ids == null ? List.of()
            : ids.stream().filter(Objects::nonNull).map(UUID::toString).toList();
        return selectPublished(actor, values, limit);
    }

    /**
     * 按字符串 ID 白名单取已发布 Skill，供内部与 {@code get_skills} 分流复用。
     *
     * @param actor 当前主体，可见性判定决定档位
     * @param ids   ID 白名单，为空表示不限
     * @param limit 返回上限
     * @return 已发布且可见的 Skill，按更新时间倒序
     */
    private List<SkillEntity> selectPublished(ActorContext actor, List<String> ids, int limit) {
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<SkillEntity>()
            .eq(SkillEntity::getUserId, actor.userId().toString())
            .eq(SkillEntity::getStatus, SkillStatus.PUBLISHED.getValue())
            .in(ids != null && !ids.isEmpty(), SkillEntity::getId, ids == null ? List.of() : ids);
        applyVisibility(wrapper, actor);
        wrapper.orderByDesc(SkillEntity::getUpdatedAt).last("LIMIT " + limit);
        return skillMapper.selectList(wrapper);
    }

    private void applyVisibility(LambdaQueryWrapper<SkillEntity> wrapper, ActorContext actor) {
        if (actor.isOwner() || actor.isSystem()) {
            return;
        }
        if (actor.agentClientId() != null) {
            wrapper.in(SkillEntity::getVisibility,
                List.of(Visibility.AGENT.getValue(), Visibility.PUBLIC.getValue()));
        } else {
            wrapper.eq(SkillEntity::getVisibility, Visibility.PUBLIC.getValue());
        }
    }

    private List<SkillEntity> readable(ActorContext actor, List<SkillEntity> rows) {
        return rows.stream()
            .filter(row -> SkillStatus.PUBLISHED.getValue().equals(row.getStatus()))
            .filter(row -> AccessPolicy.readableBy(actor, row.getVisibility()))
            .toList();
    }

    /**
     * 一次召回结果。
     *
     * @param candidates 候选 Skill，按相关度或更新时间倒序
     * @param fullText   是否由全文检索命中；false 表示走了 {@code LIKE} 兜底
     */
    public record SkillRecall(List<SkillEntity> candidates, boolean fullText) {

        /**
         * 空召回。
         *
         * @return 结果为空、路径记为兜底的召回
         */
        public static SkillRecall empty() {
            return new SkillRecall(List.of(), false);
        }
    }
}
