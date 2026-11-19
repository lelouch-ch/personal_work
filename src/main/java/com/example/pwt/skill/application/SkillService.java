package com.example.pwt.skill.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.skill.domain.SkillSlug;
import com.example.pwt.skill.domain.SkillStatus;
import com.example.pwt.skill.infrastructure.SkillEntity;
import com.example.pwt.skill.infrastructure.SkillMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 的写入、发布与读取（设计文档 5.2、7 skill 模块、10.2、15.2）。
 *
 * <p><b>版本语义</b>：同一 slug 的 {@code draft} 可原地改；一旦 {@code published}，
 * 改正文会新建 {@code version + 1} 的草稿行，已发布内容保持不动。两个理由：
 * <ul>
 *   <li>15.2 要求「Skill 发布前由 Owner 审核」——若发布后能就地改写，Agent 正在使用的
 *       流程会在两次调用之间被换掉，那次改写实际上绕过了发布这道审核；</li>
 *   <li>唯一键是 {@code (user_id, slug, version)}，本就为多版本共存而设计。</li>
 * </ul>
 * 要让新版本生效，Owner 再显式发布它：发布动作会归档同一 slug 的旧已发布版本，
 * 保证同一 slug 最多一条生效，避免同一份流程的两个版本同时注入上下文。
 *
 * <p>写入侧只有 Owner：10.2 把 Skill 的三个接口都标为 Owner，Agent 通过 MCP 读
 * （{@code get_skills} 与 {@code pwt://skills/list}），不能自己给自己加流程。
 *
 * @author pwt
 */
@Service
public class SkillService {

    /** 名称长度上限，与列宽一致。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** 正文长度上限：与简历正文同级，防止一条超长 Skill 挤满上下文预算。 */
    private static final int MAX_CONTENT_LENGTH = 30000;

    /** 说明长度上限。 */
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    /** 单条 Skill 可关联的来源记忆条数上限。 */
    private static final int MAX_SOURCE_MEMORY_IDS = 20;

    private final SkillMapper skillMapper;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param skillMapper   Skill Mapper
     * @param auditRecorder 审计记录器
     */
    public SkillService(SkillMapper skillMapper, AuditRecorder auditRecorder) {
        this.skillMapper = skillMapper;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 分页列出 Skill，含草稿。
     *
     * @param actor    当前主体，仅 Owner
     * @param status   状态过滤，可为空
     * @param page     分页参数
     * @return 分页结果，按更新时间倒序
     */
    @Transactional(readOnly = true)
    public PageResult<SkillEntity> list(ActorContext actor, String status, PageQuery page) {
        requireOwner(actor, "查看 Skill 列表");
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<SkillEntity>()
            .eq(SkillEntity::getUserId, actor.userId().toString())
            .eq(status != null && !status.isBlank(), SkillEntity::getStatus, status);
        wrapper.orderByDesc(SkillEntity::getUpdatedAt);
        Page<SkillEntity> result = skillMapper.selectPage(new Page<>(page.page(), page.size()), wrapper);
        return PageResult.of(result.getRecords(), page.page(), page.size(), result.getTotal());
    }

    /**
     * 读取单条 Skill，跨用户与无权一律按不存在处理。
     *
     * @param actor   当前主体
     * @param skillId Skill ID
     * @return Skill
     * @throws BizException 不存在或可见性不允许读取
     */
    @Transactional(readOnly = true)
    public SkillEntity read(ActorContext actor, UUID skillId) {
        requireScopeRead(actor);
        SkillEntity entity = skillMapper.selectById(skillId.toString());
        if (entity == null || !entity.getUserId().equals(actor.userId().toString())) {
            throw BizException.notFound("skill");
        }
        // 草稿只给 Owner：Agent 拿到未审核完的流程正是 15.2 要避免的事
        if (!actor.isOwner() && SkillStatus.DRAFT.getValue().equals(entity.getStatus())) {
            throw BizException.notFound("skill");
        }
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            throw BizException.notFound("skill");
        }
        return entity;
    }

    /**
     * 创建 Skill，初始为草稿。
     *
     * @param actor   当前主体，仅 Owner
     * @param command 内容
     * @return 落库后的 Skill
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillEntity create(ActorContext actor, SkillCommand command) {
        requireOwner(actor, "创建 Skill");
        String slug = SkillSlug.normalize(command.slug());
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        String contentMd = requireText(command.contentMd(), "contentMd", MAX_CONTENT_LENGTH);
        String description = optionalText(command.description(), "description", MAX_DESCRIPTION_LENGTH);
        AccessPolicy.requireAssignable(actor, command.visibility());

        SkillEntity entity = new SkillEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setSlug(slug);
        entity.setName(name);
        entity.setDescription(description);
        entity.setContentMd(contentMd);
        entity.setVersion(skillMapper.selectMaxVersion(actor.userId().toString(), slug) + 1);
        entity.setStatus(SkillStatus.DRAFT.getValue());
        entity.setVisibility(Visibility.fromValueOrDefault(command.visibility(), Visibility.PRIVATE).getValue());
        entity.setSourceMemoryIds(normalizeSourceIds(command.sourceMemoryIds()));
        Instant now = Instant.now();
        // updated_at 必须显式赋值：MyBatis-Plus 会把读到的旧值写回该列（设计文档 8.4）
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            skillMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一 slug 并发创建同一版本：交由调用方重试，不静默覆盖已有内容
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "Skill 版本创建冲突，请重试", e);
        }
        audit(actor, "skill.create", entity.getId());
        return entity;
    }

    /**
     * 更新 Skill：草稿原地改，已发布则产生新草稿版本。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId 目标 Skill ID
     * @param command 新内容；{@code slug} 以现有值为准，不允许在此改
     * @return 被修改的行——已发布入参会是新建的草稿行，调用方据 {@code status} 判断是否还需发布
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillEntity update(ActorContext actor, UUID skillId, SkillCommand command) {
        requireOwner(actor, "修改 Skill");
        SkillEntity existing = requireOwn(actor, skillId);
        String name = requireText(command.name(), "name", MAX_NAME_LENGTH);
        String contentMd = requireText(command.contentMd(), "contentMd", MAX_CONTENT_LENGTH);
        String description = optionalText(command.description(), "description", MAX_DESCRIPTION_LENGTH);
        AccessPolicy.requireAssignable(actor, command.visibility());
        List<String> sourceIds = normalizeSourceIds(command.sourceMemoryIds());

        if (SkillStatus.ARCHIVED.getValue().equals(existing.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "已归档的 Skill 不能直接修改，请新建版本");
        }
        if (SkillStatus.DRAFT.getValue().equals(existing.getStatus())) {
            existing.setName(name);
            existing.setDescription(description);
            existing.setContentMd(contentMd);
            existing.setVisibility(
                Visibility.fromValueOrDefault(command.visibility(), Visibility.PRIVATE).getValue());
            existing.setSourceMemoryIds(sourceIds);
            existing.setUpdatedAt(Instant.now());
            skillMapper.updateById(existing);
            audit(actor, "skill.update", existing.getId());
            return existing;
        }
        // 已发布：留版本，不覆盖 Agent 正在用的内容
        SkillEntity next = new SkillEntity();
        next.setId(Uuids.randomUuidv7String());
        next.setUserId(existing.getUserId());
        next.setSlug(existing.getSlug());
        next.setName(name);
        next.setDescription(description);
        next.setContentMd(contentMd);
        next.setVersion(skillMapper.selectMaxVersion(existing.getUserId(), existing.getSlug()) + 1);
        next.setStatus(SkillStatus.DRAFT.getValue());
        next.setVisibility(
            Visibility.fromValueOrDefault(command.visibility(), Visibility.PRIVATE).getValue());
        next.setSourceMemoryIds(sourceIds);
        Instant now = Instant.now();
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        try {
            skillMapper.insert(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "Skill 版本创建冲突，请重试", e);
        }
        audit(actor, "skill.new_version", next.getId());
        return next;
    }

    /**
     * 发布 Skill：目标转 {@code published}，同一 slug 的其它已发布版本转 {@code archived}。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId 目标 Skill ID
     * @return 发布后的 Skill
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillEntity publish(ActorContext actor, UUID skillId) {
        requireOwner(actor, "发布 Skill");
        SkillEntity entity = requireOwn(actor, skillId);
        SkillStatus status = SkillStatus.fromValue(entity.getStatus());
        if (!status.canPublish()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "只有草稿可以发布，当前状态为 " + entity.getStatus());
        }
        archivePublishedSiblings(actor.userId().toString(), entity.getSlug(), entity.getId());
        entity.setStatus(SkillStatus.PUBLISHED.getValue());
        entity.setUpdatedAt(Instant.now());
        skillMapper.updateById(entity);
        audit(actor, "skill.publish", entity.getId());
        return entity;
    }

    /**
     * 归档 Skill。
     *
     * @param actor   当前主体，仅 Owner
     * @param skillId 目标 Skill ID
     * @return 归档后的 Skill
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillEntity archive(ActorContext actor, UUID skillId) {
        requireOwner(actor, "归档 Skill");
        SkillEntity entity = requireOwn(actor, skillId);
        if (SkillStatus.ARCHIVED.getValue().equals(entity.getStatus())) {
            return entity;
        }
        entity.setStatus(SkillStatus.ARCHIVED.getValue());
        entity.setUpdatedAt(Instant.now());
        skillMapper.updateById(entity);
        audit(actor, "skill.archive", entity.getId());
        return entity;
    }

    private void archivePublishedSiblings(String userId, String slug, String keepId) {
        List<SkillEntity> published = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
            .eq(SkillEntity::getUserId, userId)
            .eq(SkillEntity::getSlug, slug)
            .eq(SkillEntity::getStatus, SkillStatus.PUBLISHED.getValue())
            .ne(SkillEntity::getId, keepId));
        for (SkillEntity sibling : published) {
            sibling.setStatus(SkillStatus.ARCHIVED.getValue());
            sibling.setUpdatedAt(Instant.now());
            skillMapper.updateById(sibling);
        }
    }

    private SkillEntity requireOwn(ActorContext actor, UUID skillId) {
        SkillEntity entity = skillMapper.selectById(skillId.toString());
        if (entity == null || !entity.getUserId().equals(actor.userId().toString())) {
            // 跨用户按不存在处理，避免泄露资源存在性（14.3）
            throw BizException.notFound("skill");
        }
        return entity;
    }

    private List<String> normalizeSourceIds(List<String> sourceMemoryIds) {
        if (sourceMemoryIds == null || sourceMemoryIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : sourceMemoryIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(raw.trim()).toString());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "sourceMemoryIds 含非法 UUID: " + raw);
            }
            if (ids.size() > MAX_SOURCE_MEMORY_IDS) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "sourceMemoryIds 数量不能超过 " + MAX_SOURCE_MEMORY_IDS);
            }
        }
        return new ArrayList<>(ids);
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, field + " 不能为空");
        }
        if (value.length() > max) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, field + " 长度不能超过 " + max);
        }
        return value.trim();
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, field + " 长度不能超过 " + max);
        }
        return value.trim();
    }

    private void requireScopeRead(ActorContext actor) {
        actor.requireScope(Scope.SKILL_READ);
    }

    private void requireOwner(ActorContext actor, String action) {
        if (!actor.isOwner()) {
            // 10.2 把 Skill 接口标为 Owner 专属：Agent 只消费流程，不能自行增改
            throw new BizException(ErrorCode.FORBIDDEN, "只有 Owner 可以" + action);
        }
    }

    private void audit(ActorContext actor, String action, String skillId) {
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(),
            actor.agentClientId() == null ? actor.userId() : actor.agentClientId(),
            action, "skill", UUID.fromString(skillId)));
    }
}
