package com.example.pwt.memory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.PageResult;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.SourceType;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.AccessPolicy;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.memory.domain.ApprovalDecision;
import com.example.pwt.memory.domain.ApprovalPolicy;
import com.example.pwt.memory.domain.ConflictDetector;
import com.example.pwt.memory.domain.MemoryContent;
import com.example.pwt.memory.domain.MemoryStatus;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.domain.ReviewDecision;
import com.example.pwt.memory.domain.RiskLevel;
import com.example.pwt.memory.domain.SensitiveFinder;
import com.example.pwt.memory.infrastructure.MemoryEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceEntity;
import com.example.pwt.memory.infrastructure.MemoryEvidenceMapper;
import com.example.pwt.memory.infrastructure.MemoryMapper;
import com.example.pwt.memory.infrastructure.MemoryReviewEntity;
import com.example.pwt.memory.infrastructure.MemoryReviewMapper;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆服务：候选提交、去重、冲突检测、风险分级与审核（设计文档 6.1～6.6、10.2、10.4）。
 *
 * <p>风险级别与自动批准结论一律由服务端计算，不采信调用方或模型自报（6.2）。
 *
 * <p>去重不新建重复行：同一用户同类型的相同内容只做证据累加，再按累加后的证据条数
 * 重新判定是否满足自动批准条件（6.3 第 3 条“多次证据”）。
 *
 * @author pwt
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 正文最大长度，与设计文档 9.4 的 propose_memory Schema 一致。 */
    private static final int MAX_CONTENT_LENGTH = 10000;

    /** 摘要最大长度，与列宽一致。 */
    private static final int MAX_SUMMARY_LENGTH = 1000;

    /** 证据摘录最大长度，与 9.4 的 evidence 上限一致。 */
    private static final int MAX_EVIDENCE_LENGTH = 5000;

    /** 冲突检测的近似召回条数。 */
    private static final int CONFLICT_RECALL_LIMIT = 5;

    /** 置信度列宽 decimal(5,4)，四舍五入到 4 位小数。 */
    private static final int CONFIDENCE_SCALE = 4;

    /** L3 命中后写入库的占位正文。 */
    private static final String REDACTED_CONTENT = "[已脱敏：命中凭证类敏感信息]";

    private final MemoryMapper memoryMapper;

    private final MemoryEvidenceMapper evidenceMapper;

    private final MemoryReviewMapper reviewMapper;

    private final WorkLogMapper workLogMapper;

    private final ApprovalPolicy approvalPolicy;

    private final AuditRecorder auditRecorder;

    /**
     * 构造服务。
     *
     * @param memoryMapper    记忆 Mapper
     * @param evidenceMapper  证据 Mapper
     * @param reviewMapper    审核 Mapper
     * @param workLogMapper   工作记录 Mapper，用于校验来源与判断原文支持
     * @param approvalPolicy  审核策略
     * @param auditRecorder   审计记录器
     */
    public MemoryService(MemoryMapper memoryMapper, MemoryEvidenceMapper evidenceMapper,
                         MemoryReviewMapper reviewMapper, WorkLogMapper workLogMapper,
                         ApprovalPolicy approvalPolicy, AuditRecorder auditRecorder) {
        this.memoryMapper = memoryMapper;
        this.evidenceMapper = evidenceMapper;
        this.reviewMapper = reviewMapper;
        this.workLogMapper = workLogMapper;
        this.approvalPolicy = approvalPolicy;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 提交记忆候选：Agent 走 {@code propose_memory}，Owner 手动新建同样经此入口。
     *
     * <p>Owner 手动创建视为“明确陈述”，在风险与冲突允许时可直接批准（6.3 第 1 条）。
     *
     * @param actor   当前主体，Agent 需 {@code memory:propose}
     * @param command 候选内容
     * @return 落库后的记忆，状态由审核策略决定
     * @throws BizException 参数非法、来源工作记录不属于当前用户
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntity propose(ActorContext actor, MemoryProposeCommand command) {
        // Owner 不受 scope 限制（14.2），只有 Agent 需要 memory:propose
        actor.requireScope(Scope.MEMORY_PROPOSE);
        validate(command);
        AccessPolicy.requireAssignable(actor, command.visibility());

        MemoryType type = MemoryType.fromValue(command.memoryType());
        String content = command.content().strip();
        String hash = MemoryContent.normalizedHash(type, content);

        WorkLogEntity source = requireSourceIfNeeded(actor, command.sourceWorkLogId());
        RiskLevel risk = serverRisk(content, command.summary(), command.evidence());

        List<MemoryEntity> duplicates = memoryMapper.selectDuplicates(actor.userId().toString(),
            type.getValue(), hash, null);
        List<String> similar = recallSimilar(actor.userId(), type, content, null);
        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect(content, similar);

        boolean ownerDeclared = actor.isOwner();
        int evidenceCount = evidenceCount(command, source, ownerDeclared);
        ApprovalDecision decision = approvalPolicy.decide(new ApprovalPolicy.CandidateFacts(
            type, risk, toConfidence(command.confidence()), evidenceCount,
            sourceType(actor), supportedBySource(content, command.evidence(), source), ownerDeclared,
            !duplicates.isEmpty(), !conflicts.isEmpty()));

        if (decision.decision() == ReviewDecision.REJECT) {
            // 凭证类内容要留下拒绝记录，但正文不能带秘密原样入库（15.1 第 2/3 条）
            return rejectCredential(actor, command, type, risk, decision);
        }
        if (!duplicates.isEmpty()) {
            return accumulateEvidence(actor, duplicates.get(0), command, source, risk);
        }
        MemoryEntity entity = insertMemory(actor, command, type, content, hash, risk, decision, evidenceCount,
            source);
        insertEvidence(actor, entity, command, source, null);
        insertReview(actor, entity, decision, risk, conflicts, evidenceCount);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "memory.propose", "memory", UUID.fromString(entity.getId())));
        log.info("记忆候选已入库 memoryId={} type={} risk={} status={}",
            entity.getId(), type.getValue(), risk, entity.getStatus());
        return entity;
    }

    /**
     * 分页查询记忆。
     *
     * @param actor  当前主体，需 {@code memory:read}
     * @param type   类型过滤，可为空
     * @param status 状态过滤，可为空
     * @param query  分页参数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<MemoryEntity> list(ActorContext actor, String type, String status, PageQuery query) {
        actor.requireScope(Scope.MEMORY_READ);
        LambdaQueryWrapper<MemoryEntity> wrapper = new LambdaQueryWrapper<MemoryEntity>()
            .eq(MemoryEntity::getUserId, actor.userId().toString())
            .isNull(MemoryEntity::getDeletedAt)
            .eq(type != null && !type.isBlank(), MemoryEntity::getMemoryType, type)
            .eq(status != null && !status.isBlank(), MemoryEntity::getStatus, status);
        applyVisibilityFilter(wrapper, actor);
        boolean recentFirst = !MemoryStatus.APPROVED.getValue().equals(status);
        if (recentFirst) {
            wrapper.orderByDesc(MemoryEntity::getCreatedAt);
        } else {
            wrapper.orderByDesc(MemoryEntity::getUpdatedAt).orderByDesc(MemoryEntity::getCreatedAt);
        }
        Page<MemoryEntity> page = memoryMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(page.getRecords(), query.page(), query.size(), page.getTotal());
    }

    /**
     * 待审核候选列表，只供 Owner 的审核界面使用（设计文档 10.2 将 pending 标为 Owner 专属）。
     *
     * <p>不复用 {@link #list}：那条路径允许持有 {@code memory:read} 的 Agent 调用，
     * 而审核队列属于治理入口，Agent 看到自己的候选被谁批过并无必要。
     *
     * @param actor 当前主体，仅 Owner
     * @param query 分页参数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<MemoryEntity> listPending(ActorContext actor, PageQuery query) {
        requireOwner(actor, "查看待审核队列");
        return list(actor, null, MemoryStatus.CANDIDATE.getValue(), query);
    }

    /**
     * 读取记忆详情，含证据与审核留痕（设计文档 16.3）。
     *
     * @param actor    当前主体
     * @param memoryId 记忆 ID
     * @return 详情
     * @throws BizException 不存在、跨用户或可见性不允许
     */
    @Transactional(readOnly = true)
    public MemoryDetail detail(ActorContext actor, UUID memoryId) {
        actor.requireScope(Scope.MEMORY_READ);
        MemoryEntity entity = requireExists(actor, memoryId);
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            throw BizException.notFound("memory");
        }
        return new MemoryDetail(entity,
            evidenceMapper.selectByMemory(memoryId.toString()),
            reviewMapper.selectByMemory(memoryId.toString()));
    }

    /**
     * 批准记忆。
     *
     * <p>按设计文档 10.4 在单个事务内完成：锁定记录、校验归属与状态、迁移状态、
     * 写 {@code pt_memory_review}、写 {@code pt_audit_log}。
     *
     * @param actor   当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param comment  审核备注，可为空
     * @return 批准后的记忆
     * @throws BizException Agent 试图批准、状态不允许迁移
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntity approve(ActorContext actor, UUID memoryId, String comment) {
        requireOwner(actor, "approve");
        MemoryEntity entity = lockOwned(actor, memoryId);
        MemoryStatus current = MemoryStatus.fromValue(entity.getStatus());
        if (current == MemoryStatus.APPROVED) {
            return entity;
        }
        if (!current.canApprove()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "当前状态不可批准：" + entity.getStatus());
        }
        entity.setStatus(MemoryStatus.APPROVED.getValue());
        entity.setUpdatedAt(Instant.now());
        memoryMapper.updateById(entity);
        insertOwnerReview(actor, entity, ReviewDecision.APPROVE, comment);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            "memory.approve", "memory", memoryId));
        return entity;
    }

    /**
     * 拒绝记忆。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param reason   拒绝原因，写入审核留痕
     * @return 拒绝后的记忆
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntity reject(ActorContext actor, UUID memoryId, String reason) {
        requireOwner(actor, "reject");
        MemoryEntity entity = lockOwned(actor, memoryId);
        MemoryStatus current = MemoryStatus.fromValue(entity.getStatus());
        if (!current.canReject()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前状态不可拒绝：" + entity.getStatus());
        }
        entity.setStatus(MemoryStatus.REJECTED.getValue());
        entity.setUpdatedAt(Instant.now());
        memoryMapper.updateById(entity);
        insertOwnerReview(actor, entity, ReviewDecision.REJECT, reason);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            "memory.reject", "memory", memoryId));
        return entity;
    }

    /**
     * 归档记忆。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(ActorContext actor, UUID memoryId) {
        requireOwner(actor, "archive");
        MemoryEntity entity = lockOwned(actor, memoryId);
        if (!MemoryStatus.fromValue(entity.getStatus()).canArchive()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "当前状态不可归档：" + entity.getStatus());
        }
        entity.setStatus(MemoryStatus.ARCHIVED.getValue());
        entity.setUpdatedAt(Instant.now());
        memoryMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            "memory.archive", "memory", memoryId));
    }

    /**
     * 修正记忆正文并重算去重键，对应审核界面的“编辑后批准”（设计文档 16.3、场景 C）。
     *
     * <p>已批准的记忆不原地覆盖，而是创建新版本并把旧版置为 {@code superseded}（6.5 版本策略），
     * 保留证据与历史。
     *
     * @param actor    当前主体，仅 Owner
     * @param memoryId 记忆 ID
     * @param content  新正文
     * @param summary  新摘要，可为空表示沿用
     * @param approve  是否修正后直接批准
     * @return 生效的记忆（已批准记忆返回新版本）
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntity revise(ActorContext actor, UUID memoryId, String content, String summary,
                               boolean approve) {
        requireOwner(actor, "revise");
        MemoryEntity entity = lockOwned(actor, memoryId);
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆正文不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆正文长度不能超过 " + MAX_CONTENT_LENGTH);
        }
        String newContent = content.strip();
        MemoryStatus current = MemoryStatus.fromValue(entity.getStatus());

        if (current != MemoryStatus.APPROVED) {
            applyRevision(entity, newContent, summary);
            if (approve) {
                entity.setStatus(MemoryStatus.APPROVED.getValue());
            }
            memoryMapper.updateById(entity);
            insertOwnerReview(actor, entity,
                approve ? ReviewDecision.APPROVE : ReviewDecision.MERGE, "Owner 修正后保存");
            auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
                "memory.revise", "memory", memoryId));
            return entity;
        }

        MemoryEntity revised = new MemoryEntity();
        revised.setId(Uuids.randomUuidv7String());
        revised.setUserId(entity.getUserId());
        revised.setMemoryType(entity.getMemoryType());
        revised.setVisibility(entity.getVisibility());
        revised.setRiskLevel(entity.getRiskLevel());
        revised.setImportance(entity.getImportance());
        revised.setConfidence(entity.getConfidence());
        revised.setSourceType(SourceType.MANUAL.getValue());
        revised.setSourceId(entity.getSourceId());
        revised.setProjectId(entity.getProjectId());
        revised.setVersion(entity.getVersion() == null ? 2 : entity.getVersion() + 1);
        revised.setSupersedesId(entity.getId());
        revised.setValidFrom(entity.getValidFrom());
        revised.setValidUntil(entity.getValidUntil());
        revised.setExpiresAt(entity.getExpiresAt());
        revised.setStatus(MemoryStatus.APPROVED.getValue());
        applyRevision(revised, newContent, summary);
        Instant now = Instant.now();
        revised.setCreatedAt(now);
        revised.setUpdatedAt(now);
        memoryMapper.insert(revised);

        entity.setStatus(MemoryStatus.SUPERSEDED.getValue());
        entity.setUpdatedAt(now);
        memoryMapper.updateById(entity);

        for (MemoryEvidenceEntity evidence : evidenceMapper.selectByMemory(entity.getId())) {
            MemoryEvidenceEntity copy = new MemoryEvidenceEntity();
            copy.setId(Uuids.randomUuidv7String());
            copy.setUserId(evidence.getUserId());
            copy.setMemoryId(revised.getId());
            copy.setWorkLogId(evidence.getWorkLogId());
            copy.setEvidenceType(evidence.getEvidenceType());
            copy.setExcerpt(evidence.getExcerpt());
            copy.setSourceMetadata(evidence.getSourceMetadata());
            copy.setCreatedAt(now);
            evidenceMapper.insert(copy);
        }
        insertOwnerReview(actor, revised, ReviewDecision.SUPERSEDE,
            "Owner 修正已批准记忆，替代版本 " + entity.getVersion());
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            "memory.supersede", "memory", UUID.fromString(revised.getId())));
        return revised;
    }

    /**
     * 用新记忆替代既有记忆（设计文档 6.5 版本策略、6.3 第 6 条）。
     *
     * @param actor      当前主体，仅 Owner
     * @param memoryId   既有记忆 ID
     * @param replacement 替代它的新记忆 ID
     * @param reason     原因
     * @return 替代后的新记忆
     */
    @Transactional(rollbackFor = Exception.class)
    public MemoryEntity supersede(ActorContext actor, UUID memoryId, UUID replacement, String reason) {
        requireOwner(actor, "supersede");
        MemoryEntity old = lockOwned(actor, memoryId);
        MemoryEntity next = lockOwned(actor, replacement);
        if (!MemoryStatus.fromValue(next.getStatus()).canApprove()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "替代记忆需处于候选或被拒绝状态");
        }
        Instant now = Instant.now();
        next.setStatus(MemoryStatus.APPROVED.getValue());
        next.setSupersedesId(old.getId());
        next.setVersion(old.getVersion() == null ? 2 : old.getVersion() + 1);
        next.setUpdatedAt(now);
        memoryMapper.updateById(next);

        old.setStatus(MemoryStatus.SUPERSEDED.getValue());
        old.setUpdatedAt(now);
        memoryMapper.updateById(old);

        insertOwnerReview(actor, next, ReviewDecision.SUPERSEDE, reason);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), actor.userId(),
            "memory.supersede", "memory", replacement));
        return next;
    }

    /**
     * 计算内容的服务端风险等级：敏感检测结果与入参风险取更严者（设计文档 6.2）。
     *
     * @param parts 待检测文本片段
     * @return 风险等级
     */
    public static RiskLevel serverRisk(String... parts) {
        RiskLevel risk = RiskLevel.L0;
        for (String part : parts) {
            risk = risk.max(SensitiveFinder.highestRisk(SensitiveFinder.scan(part)));
        }
        return risk;
    }

    /**
     * L3 命中：拒绝并脱敏。
     *
     * <p>不落原文：审核界面与列表都会展示 content，凭证原样入库等于把秘密换了一处存放，
     * 违背 15.1「不把原始秘密写入日志/普通记忆」。这里只保存类型、风险与固定占位正文。
     *
     * <p>{@code normalized_hash} 同样不落：它是明文摘要，低熵口令可以字典碰撞反推，
     * 存下来等于开了另一个口子。
     */
    private MemoryEntity rejectCredential(ActorContext actor, MemoryProposeCommand command, MemoryType type,
                                          RiskLevel risk, ApprovalDecision decision) {
        // 摘要同样可能复述秘密，一并丢弃；来源工作记录 ID 只是引用，可以保留
        MemoryProposeCommand stripped = new MemoryProposeCommand(command.memoryType(), command.content(),
            null, null, command.sourceWorkLogId(), command.visibility(), command.confidence(),
            command.expiresAt(), command.extractionMetadata());
        // 拒绝路径不传来源：正文已脱敏，其来源工作记录也不再作为证据落库
        MemoryEntity entity = insertMemory(actor, stripped, type, REDACTED_CONTENT, null, risk, decision, 0, null);
        insertEvidence(actor, entity, stripped, null, "redacted");
        insertReview(actor, entity, decision, risk, List.of(), 0);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "memory.reject", "memory", UUID.fromString(entity.getId())));
        log.info("记忆候选命中 L3 凭证, 已拒绝并脱敏 userId={} type={}", actor.userId(), type.getValue());
        return entity;
    }

    private MemoryEntity accumulateEvidence(ActorContext actor, MemoryEntity existing,
                                            MemoryProposeCommand command, WorkLogEntity source,
                                            RiskLevel risk) {
        insertEvidence(actor, existing, command, source, "duplicate");
        // 既有记忆可能来自人工提交（无项目归属），而这次重复提交带上了项目内的来源记录。
        // 此时补齐归属，否则这条记忆在此后的按项目检索里始终缺席（V10 迁移说明）。
        boolean projectBackfilled = existing.getProjectId() == null && source != null
            && source.getProjectId() != null;
        if (projectBackfilled) {
            existing.setProjectId(source.getProjectId());
        }
        long evidenceCount = evidenceMapper.countByMemory(existing.getId());
        MemoryStatus status = MemoryStatus.fromValue(existing.getStatus());
        if (status != MemoryStatus.CANDIDATE) {
            log.info("命中重复记忆, 仅累加证据 memoryId={} status={} evidenceCount={}",
                existing.getId(), existing.getStatus(), evidenceCount);
            if (projectBackfilled) {
                existing.setUpdatedAt(Instant.now());
                memoryMapper.updateById(existing);
            }
            return existing;
        }
        ApprovalDecision decision = approvalPolicy.decide(new ApprovalPolicy.CandidateFacts(
            MemoryType.fromValue(existing.getMemoryType()), risk.max(riskOf(existing)),
            existing.getConfidence(), (int) evidenceCount, SourceType.fromValue(existing.getSourceType()),
            supportedBySource(existing.getContent(), command.evidence(), source), actor.isOwner(),
            false, false));
        // 证据累加后仍判重，交由人工合并，不静默改状态
        ApprovalDecision finalDecision = decision.decision() == ReviewDecision.AUTO_APPROVE
            ? decision
            : ApprovalDecision.pendingReview("与既有记忆重复，已累加证据共 " + evidenceCount + " 条；"
                + decision.reason());
        applyDecision(existing, finalDecision);
        existing.setUpdatedAt(Instant.now());
        memoryMapper.updateById(existing);
        insertReview(actor, existing, finalDecision, risk.max(riskOf(existing)), List.of(),
            (int) evidenceCount);
        return existing;
    }

    private MemoryEntity insertMemory(ActorContext actor, MemoryProposeCommand command, MemoryType type,
                                      String content, String hash, RiskLevel risk,
                                      ApprovalDecision decision, int evidenceCount, WorkLogEntity source) {
        Instant now = Instant.now();
        MemoryEntity entity = new MemoryEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setMemoryType(type.getValue());
        entity.setContent(content);
        entity.setSummary(blankToNull(command.summary()));
        entity.setNormalizedHash(hash);
        entity.setConfidence(toConfidence(command.confidence()));
        entity.setImportance(defaultImportance(type));
        entity.setRiskLevel(risk.name());
        entity.setVisibility(Visibility.fromValueOrDefault(command.visibility(), Visibility.PRIVATE).getValue());
        entity.setSourceType(sourceType(actor).getValue());
        entity.setSourceId(command.sourceWorkLogId() == null ? null : command.sourceWorkLogId().toString());
        // 项目归属必须在 insert 之前落到实体上：insert 之后再改内存对象不会回写这一列，
        // 端到端实测到的表现是接口返回带 projectId 而库里存的是 NULL
        entity.setProjectId(source == null ? null : source.getProjectId());
        entity.setVersion(1);
        entity.setExpiresAt(command.expiresAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        applyDecision(entity, decision);
        memoryMapper.insert(entity);
        return entity;
    }

    private void applyDecision(MemoryEntity entity, ApprovalDecision decision) {
        entity.setStatus(decision.decision() == ReviewDecision.AUTO_APPROVE
            ? MemoryStatus.APPROVED.getValue() : MemoryStatus.CANDIDATE.getValue());
        if (decision.decision() == ReviewDecision.REJECT) {
            entity.setStatus(MemoryStatus.REJECTED.getValue());
        }
    }

    private void applyRevision(MemoryEntity entity, String content, String summary) {
        entity.setContent(content);
        entity.setNormalizedHash(MemoryContent.normalizedHash(MemoryType.fromValue(entity.getMemoryType()),
            content));
        if (summary != null && !summary.isBlank()) {
            entity.setSummary(summary.strip());
        }
        entity.setRiskLevel(serverRisk(content, entity.getSummary()).name());
        entity.setUpdatedAt(Instant.now());
    }

    private void insertEvidence(ActorContext actor, MemoryEntity memory, MemoryProposeCommand command,
                                WorkLogEntity source, String evidenceType) {
        MemoryEvidenceEntity evidence = new MemoryEvidenceEntity();
        evidence.setId(Uuids.randomUuidv7String());
        evidence.setUserId(actor.userId().toString());
        evidence.setMemoryId(memory.getId());
        evidence.setWorkLogId(source == null ? null : source.getId());
        evidence.setEvidenceType(evidenceType == null ? "source_excerpt" : evidenceType);
        // 脱敏证据只留“有过摘录”这一事实，不保存任何可还原的片段
        evidence.setExcerpt("redacted".equals(evidenceType) ? null : blankToNull(command.evidence()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", sourceType(actor).getValue());
        metadata.put("actorType", actor.actorType().name().toLowerCase());
        if (actor.agentClientId() != null) {
            metadata.put("agentClientId", actor.agentClientId().toString());
        }
        if (command.extractionMetadata() != null && !command.extractionMetadata().isEmpty()) {
            metadata.putAll(command.extractionMetadata());
        }
        evidence.setSourceMetadata(Map.copyOf(metadata));
        evidence.setCreatedAt(Instant.now());
        evidenceMapper.insert(evidence);
    }

    private void insertReview(ActorContext actor, MemoryEntity memory, ApprovalDecision decision,
                              RiskLevel risk, List<ConflictDetector.Conflict> conflicts, int evidenceCount) {
        MemoryReviewEntity review = new MemoryReviewEntity();
        review.setId(Uuids.randomUuidv7String());
        review.setUserId(actor.userId().toString());
        review.setMemoryId(memory.getId());
        review.setReviewType("AUTO");
        review.setRiskLevel(risk.name());
        review.setDecision(decision.decision().getValue());
        review.setReason(decision.reason());
        review.setReviewerType("system");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("memoryType", memory.getMemoryType());
        snapshot.put("evidenceCount", evidenceCount);
        snapshot.put("conflictCount", conflicts.size());
        // 置信度经常缺省，而 Map.copyOf 不接受 null 值，因此只在有值时放入
        if (memory.getConfidence() != null) {
            snapshot.put("confidence", memory.getConfidence().toPlainString());
        }
        if (!conflicts.isEmpty()) {
            snapshot.put("conflictReason", conflicts.get(0).reason());
        }
        review.setRuleSnapshot(Map.copyOf(snapshot));
        review.setCreatedAt(Instant.now());
        reviewMapper.insert(review);
    }

    private void insertOwnerReview(ActorContext actor, MemoryEntity memory, ReviewDecision decision,
                                   String reason) {
        MemoryReviewEntity review = new MemoryReviewEntity();
        review.setId(Uuids.randomUuidv7String());
        review.setUserId(actor.userId().toString());
        review.setMemoryId(memory.getId());
        review.setReviewType("OWNER");
        review.setRiskLevel(memory.getRiskLevel());
        review.setDecision(decision.getValue());
        review.setReason(blankToNull(reason));
        review.setReviewerType("owner");
        review.setReviewerId(actor.userId().toString());
        review.setRuleSnapshot(Map.of("memoryVersion",
            memory.getVersion() == null ? 1 : memory.getVersion()));
        review.setCreatedAt(Instant.now());
        reviewMapper.insert(review);
    }

    private List<String> recallSimilar(UUID userId, MemoryType type, String content, String excludeId) {
        String phrase = keywordOf(content);
        if (phrase.isBlank()) {
            return List.of();
        }
        return memoryMapper.selectSimilar(userId.toString(), type.getValue(), phrase, excludeId,
            CONFLICT_RECALL_LIMIT).stream().map(MemoryEntity::getContent).toList();
    }

    /**
     * 取正文前若干字符作为近似召回的检索串。
     *
     * <p>ngram 按 2 字切分，取前 24 字符已能覆盖“同一主题的两种说法”的共同开头，
     * 又不会因整段正文过长而把评分摊薄。
     *
     * @param content 正文
     * @return 检索串
     */
    static String keywordOf(String content) {
        String normalized = MemoryContent.normalize(content);
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24);
    }

    private WorkLogEntity requireSourceIfNeeded(ActorContext actor, UUID sourceWorkLogId) {
        if (sourceWorkLogId == null) {
            return null;
        }
        WorkLogEntity source = workLogMapper.selectById(sourceWorkLogId.toString());
        if (source == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "来源工作记录不存在");
        }
        actor.requireOwnership(UUID.fromString(source.getUserId()));
        return source;
    }

    private void validate(MemoryProposeCommand command) {
        if (command.content() == null || command.content().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆正文不能为空");
        }
        if (command.content().length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆正文长度不能超过 " + MAX_CONTENT_LENGTH);
        }
        if (length(command.summary()) > MAX_SUMMARY_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "摘要长度不能超过 " + MAX_SUMMARY_LENGTH);
        }
        if (length(command.evidence()) > MAX_EVIDENCE_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "证据摘录长度不能超过 " + MAX_EVIDENCE_LENGTH);
        }
        if (command.memoryType() == null || command.memoryType().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆类型不能为空");
        }
        try {
            MemoryType.fromValue(command.memoryType());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "记忆类型取值非法：" + command.memoryType());
        }
        if (command.visibility() != null && !command.visibility().isBlank()) {
            try {
                Visibility.fromValue(command.visibility());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "可见性取值非法：" + command.visibility());
            }
        }
    }

    private MemoryEntity requireExists(ActorContext actor, UUID memoryId) {
        MemoryEntity entity = memoryMapper.selectById(memoryId.toString());
        if (entity == null || entity.getDeletedAt() != null) {
            throw BizException.notFound("memory");
        }
        actor.requireOwnership(UUID.fromString(entity.getUserId()));
        return entity;
    }

    private MemoryEntity lockOwned(ActorContext actor, UUID memoryId) {
        MemoryEntity entity = memoryMapper.selectForUpdate(memoryId.toString());
        if (entity == null || entity.getDeletedAt() != null) {
            throw BizException.notFound("memory");
        }
        actor.requireOwnership(UUID.fromString(entity.getUserId()));
        return entity;
    }

    private void requireOwner(ActorContext actor, String action) {
        if (!actor.isOwner()) {
            // 设计文档 10.2 与 19.2 用例 5：审核动作只属于 Owner，Agent 即便有 memory:propose 也不能自批
            throw new BizException(ErrorCode.FORBIDDEN, "只有 Owner 可以" + action + "记忆");
        }
    }

    private void applyVisibilityFilter(LambdaQueryWrapper<MemoryEntity> wrapper, ActorContext actor) {
        if (actor.isOwner() || actor.isSystem()) {
            return;
        }
        if (actor.agentClientId() != null) {
            wrapper.in(MemoryEntity::getVisibility, List.of(Visibility.AGENT.getValue(),
                Visibility.PUBLIC.getValue()));
        } else {
            wrapper.eq(MemoryEntity::getVisibility, Visibility.PUBLIC.getValue());
        }
    }

    private int evidenceCount(MemoryProposeCommand command, WorkLogEntity source, boolean ownerDeclared) {
        if (ownerDeclared) {
            return 1;
        }
        // 单次提交最多算一条证据：来源工作记录与随附摘录指向同一事实，不能各计一次。
        // 否则单条记录就能凑满“多次证据”，让 6.3 第 3 条对本应攒够多次实践的偏好/经验形同虚设
        // （5.2：不是每条记录都该自动变成长期记忆）。跨记录的证据累加由 accumulateEvidence 负责。
        if (source != null) {
            return 1;
        }
        return (command.evidence() == null || command.evidence().isBlank()) ? 0 : 1;
    }

    /**
     * 判断候选内容是否被来源原文直接支持：正文与证据均取自原文即视为受支持。
     *
     * <p>用包含关系而不是模型自评（6.3 第 2 条“原文直接支持”），判定可复现且不会被
     * 模型幻觉绕过。
     *
     * @param content  候选正文
     * @param evidence 证据摘录
     * @param source   来源工作记录，可为空
     * @return true 表示受支持
     */
    static boolean supportedBySource(String content, String evidence, WorkLogEntity source) {
        if (source == null) {
            return false;
        }
        String sourceText = MemoryContent.normalize(
            nullToEmpty(source.getTitle()) + " " + nullToEmpty(source.getSummary()) + " "
                + nullToEmpty(source.getContent()));
        if (evidence != null && !evidence.isBlank()) {
            return sourceText.contains(MemoryContent.normalize(evidence));
        }
        String normalized = MemoryContent.normalize(content);
        return !normalized.isEmpty() && sourceText.contains(normalized);
    }

    private static SourceType sourceType(ActorContext actor) {
        if (actor.isOwner()) {
            return SourceType.MANUAL;
        }
        return actor.isSystem() ? SourceType.SYSTEM : SourceType.AGENT;
    }

    private static RiskLevel riskOf(MemoryEntity entity) {
        try {
            return RiskLevel.fromValue(entity.getRiskLevel());
        } catch (IllegalArgumentException e) {
            return RiskLevel.L1;
        }
    }

    private static int defaultImportance(MemoryType type) {
        return switch (type) {
            case CONSTRAINT, DECISION -> 4;
            case FACT, PREFERENCE, EXPERIENCE, GOAL -> 3;
        };
    }

    private static BigDecimal toConfidence(Double value) {
        if (value == null) {
            return null;
        }
        double bounded = Math.max(0d, Math.min(1d, value));
        return BigDecimal.valueOf(bounded).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private UUID subjectId(ActorContext actor) {
        return actor.agentClientId() == null ? actor.userId() : actor.agentClientId();
    }

    /**
     * 供提取任务复用：按类型与内容提交候选。
     *
     * @param actor   主体
     * @param type    记忆类型
     * @param content 正文
     * @param summary 摘要
     * @param evidence 证据摘录
     * @param sourceWorkLogId 来源工作记录
     * @param confidence 置信度
     * @return 落库后的记忆
     */
    public MemoryEntity proposeExtracted(ActorContext actor, MemoryType type, String content, String summary,
                                         String evidence, UUID sourceWorkLogId, Double confidence,
                                         Map<String, Object> extractionMetadata) {
        return propose(actor, new MemoryProposeCommand(type.getValue(), content, summary, evidence,
            sourceWorkLogId, Visibility.PRIVATE.getValue(), confidence, null,
            extractionMetadata == null ? Map.of() : Map.copyOf(extractionMetadata)));
    }

    /**
     * 供工作台指标使用：统计待审核候选数量（设计文档 16.2）。
     *
     * @param userId 用户
     * @return 候选条数
     */
    @Transactional(readOnly = true)
    public long countPending(UUID userId) {
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<MemoryEntity>()
            .eq(MemoryEntity::getUserId, userId.toString())
            .eq(MemoryEntity::getStatus, MemoryStatus.CANDIDATE.getValue())
            .isNull(MemoryEntity::getDeletedAt));
        return Optional.ofNullable(count).orElse(0L);
    }
}
