package com.example.pwt.work.application;

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
import com.example.pwt.task.application.TaskService;
import com.example.pwt.task.domain.TaskPayload;
import com.example.pwt.task.domain.TaskStatus;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.work.domain.ExtractionState;
import com.example.pwt.work.domain.WorkLogStatus;
import com.example.pwt.work.domain.WorkType;
import com.example.pwt.work.infrastructure.ProjectEntity;
import com.example.pwt.work.infrastructure.ProjectMapper;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作记录服务（设计文档 3.2 场景 A、10.2、M3）。
 *
 * <p>写入路径同时服务业主手动录入与 Agent 的 {@code capture_work_log}，
 * 落库后由调用方决定是否需要创建记忆提取任务（M4 接入异步任务表）。
 *
 * @author pwt
 */
@Service
public class WorkLogService {

    private static final Logger log = LoggerFactory.getLogger(WorkLogService.class);

    /** 标题最大长度，与设计文档 9.4 的 capture_work_log Schema 一致。 */
    private static final int MAX_TITLE_LENGTH = 300;

    /** 摘要最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 5000;

    /** 正文最大长度。 */
    private static final int MAX_CONTENT_LENGTH = 30000;

    /** 标签数量上限。 */
    private static final int MAX_TAG_COUNT = 20;

    /** 单个标签最大长度。 */
    private static final int MAX_TAG_LENGTH = 50;

    /** 全文检索返回上限。 */
    private static final int MAX_SEARCH_LIMIT = 50;

    private final WorkLogMapper workLogMapper;

    private final ProjectMapper projectMapper;

    private final AuditRecorder auditRecorder;

    private final TaskService taskService;

    /**
     * 构造服务。
     *
     * @param workLogMapper 工作记录 Mapper
     * @param projectMapper 项目 Mapper，用于校验关联项目归属
     * @param auditRecorder 审计记录器
     * @param taskService   异步任务服务，用于排队提取任务
     */
    public WorkLogService(WorkLogMapper workLogMapper, ProjectMapper projectMapper,
                          AuditRecorder auditRecorder, TaskService taskService) {
        this.workLogMapper = workLogMapper;
        this.projectMapper = projectMapper;
        this.auditRecorder = auditRecorder;
        this.taskService = taskService;
    }

    /**
     * 创建工作记录。
     *
     * <p>幂等：携带 {@code idempotencyKey} 时，同一用户重复提交不产生第二条记录，
     * 而是返回既有记录（设计文档 17.3、验收用例 1）。
     *
     * <p>新建成功后在同一事务内排队 {@code EXTRACT_MEMORY} 任务（设计文档 6.1 的 B→C 步、
     * 验收用例 3）。同事务可保证业务回滚时不留悬空任务；任务按工作记录 ID 去重，
     * 因此幂等命中或并发重复提交都不会排队两次。
     *
     * @param actor   当前主体，Agent 需具备 {@code work:write}
     * @param command 记录内容
     * @return 记录本体与本次是否排队了提取任务
     * @throws BizException 参数非法、关联项目不属于当前用户
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkLogCreation create(ActorContext actor, WorkLogCommand command) {
        actor.requireScope(Scope.WORK_WRITE);
        validate(command);
        AccessPolicy.requireAssignable(actor, command.visibility());

        if (command.projectId() != null) {
            requireProjectOwned(actor, command.projectId());
        }
        String sourceType = actor.isOwner() ? SourceType.MANUAL.getValue() : SourceType.AGENT.getValue();
        // 可见性取值统一处理：Agent 提交 public 已由上面的 requireAssignable 拒绝（设计文档 9.6），
        // 此处不再维护第二套降级语义
        String visibility = Visibility.fromValueOrDefault(command.visibility(), Visibility.PRIVATE).getValue();

        Optional<WorkLogEntity> existing = findByIdempotencyKey(actor, command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("幂等命中, 返回既有记录 userId={} key={}", actor.userId(), command.idempotencyKey());
            return new WorkLogCreation(existing.get(), extractionState(actor, existing.get().getId()));
        }

        WorkLogEntity entity = new WorkLogEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUserId(actor.userId().toString());
        entity.setProjectId(command.projectId() == null ? null : command.projectId().toString());
        entity.setAgentClientId(actor.agentClientId() == null ? null : actor.agentClientId().toString());
        entity.setSourceType(sourceType);
        entity.setTitle(command.title().trim());
        entity.setSummary(command.summary());
        entity.setContent(command.content());
        entity.setWorkType(WorkType.fromValueOrDefault(command.workType(), WorkType.GENERAL).getValue());
        entity.setVisibility(visibility);
        entity.setStatus(WorkLogStatus.ACTIVE.getValue());
        entity.setOccurredAt(command.occurredAt());
        entity.setTags(normalizeTags(command.tags()));
        entity.setIdempotencyKey(blankToNull(command.idempotencyKey()));
        entity.setCreatedAt(Instant.now());
        // 新建行的 updatedAt 与 createdAt 同值：列默认值只落在数据库侧，
        // 不回填实体就会让创建响应带 null，与随后查询详情得到的结果不一致（设计文档 8.4 第 5 条）
        entity.setUpdatedAt(entity.getCreatedAt());

        try {
            workLogMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引 uq_work_log_idempotency 拦截后回读既有记录，保持幂等语义
            WorkLogEntity raced = findByIdempotencyKey(actor, command.idempotencyKey())
                .orElseThrow(() -> new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "幂等键冲突但未能回读既有记录", e));
            return new WorkLogCreation(raced, extractionState(actor, raced.getId()));
        }

        AsyncTaskEntity task = taskService.enqueue(actor.userId(), TaskType.EXTRACT_MEMORY, entity.getId(),
            TaskPayload.ofWorkLog(UUID.fromString(entity.getId())));
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "work_log.create", "work_log", UUID.fromString(entity.getId())));
        return new WorkLogCreation(entity, toExtractionState(TaskStatus.fromValue(task.getStatus())));
    }

    /**
     * 读取指定记录当前的提取任务进度，用于幂等命中时的响应。
     *
     * @param actor 当前主体
     * @param logId 工作记录 ID
     * @return 提取进度，未排队时为 {@link ExtractionState#NOT_QUEUED}
     */
    public ExtractionState extractionState(ActorContext actor, String logId) {
        AsyncTaskEntity task = taskService.find(actor.userId(), TaskType.EXTRACT_MEMORY, logId);
        return task == null ? ExtractionState.NOT_QUEUED
            : toExtractionState(TaskStatus.fromValue(task.getStatus()));
    }

    private static ExtractionState toExtractionState(TaskStatus status) {
        return switch (status) {
            case PENDING -> ExtractionState.QUEUED;
            case RUNNING -> ExtractionState.RUNNING;
            case SUCCEEDED -> ExtractionState.SUCCEEDED;
            case FAILED, CANCELLED -> ExtractionState.FAILED;
        };
    }

    /**
     * 分页查询工作记录。
     *
     * @param actor     当前主体，需具备 {@code work:read}
     * @param projectId 项目过滤，可为空
     * @param from      起始发生时间，可为空
     * @param to        截止发生时间，可为空
     * @param query     分页参数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<WorkLogEntity> list(ActorContext actor, UUID projectId, Instant from, Instant to,
                                          PageQuery query) {
        actor.requireScope(Scope.WORK_READ);
        LambdaQueryWrapper<WorkLogEntity> wrapper = new LambdaQueryWrapper<WorkLogEntity>()
            .eq(WorkLogEntity::getUserId, actor.userId().toString())
            .ne(WorkLogEntity::getStatus, WorkLogStatus.DELETED.getValue())
            .eq(projectId != null, WorkLogEntity::getProjectId, projectId == null ? null : projectId.toString())
            .ge(from != null, WorkLogEntity::getOccurredAt, from)
            .le(to != null, WorkLogEntity::getOccurredAt, to)
            .orderByDesc(WorkLogEntity::getOccurredAt);
        applyVisibilityFilter(wrapper, actor);
        Page<WorkLogEntity> page = workLogMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(page.getRecords(), query.page(), query.size(), page.getTotal());
    }

    /**
     * 读取单条记录详情。
     *
     * @param actor   当前主体
     * @param logId   记录 ID
     * @return 记录
     * @throws BizException 不存在、不属于该用户或可见性不允许
     */
    @Transactional(readOnly = true)
    public WorkLogEntity requireReadable(ActorContext actor, UUID logId) {
        actor.requireScope(Scope.WORK_READ);
        WorkLogEntity entity = workLogMapper.selectById(logId.toString());
        if (entity == null || WorkLogStatus.DELETED.getValue().equals(entity.getStatus())) {
            throw BizException.notFound("work log");
        }
        actor.requireOwnership(UUID.fromString(entity.getUserId()));
        if (!AccessPolicy.readableBy(actor, entity.getVisibility())) {
            throw BizException.notFound("work log");
        }
        return entity;
    }

    /**
     * 按标签检索。
     *
     * @param actor 当前主体
     * @param tag   标签，大小写不敏感
     * @param limit 返回上限，最大 50
     * @return 命中记录
     */
    @Transactional(readOnly = true)
    public List<WorkLogEntity> searchByTag(ActorContext actor, String tag, int limit) {
        actor.requireScope(Scope.WORK_READ);
        if (tag == null || tag.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "标签不能为空");
        }
        List<WorkLogEntity> rows = workLogMapper.selectByTag(normalizeTag(tag), null, capLimit(limit));
        return filterReadable(actor, rows);
    }

    /**
     * 关键词全文检索。
     *
     * <p>使用自然语言模式而非布尔模式：布尔模式受 {@code ngram_token_size = 2} 影响，
     * 输入中的分隔符与操作符会改变语义，直接把用户输入当查询串不安全。
     *
     * @param actor 当前主体
     * @param keyword 关键词
     * @param limit 返回上限，最大 50
     * @return 命中记录，按相关度倒序
     */
    @Transactional(readOnly = true)
    public List<WorkLogEntity> searchByKeyword(ActorContext actor, String keyword, int limit) {
        actor.requireScope(Scope.WORK_READ);
        if (keyword == null || keyword.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "关键词不能为空");
        }
        List<WorkLogEntity> rows = workLogMapper.searchFullText(keyword.trim(), null, capLimit(limit));
        return filterReadable(actor, rows);
    }

    /**
     * 归档记录。
     *
     * @param actor 当前主体
     * @param logId 记录 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(ActorContext actor, UUID logId) {
        actor.requireScope(Scope.WORK_WRITE);
        WorkLogEntity entity = requireOwned(actor, logId);
        entity.setStatus(WorkLogStatus.ARCHIVED.getValue());
        entity.setUpdatedAt(Instant.now());
        workLogMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "work_log.archive", "work_log", logId));
    }

    /**
     * 调整可见性。
     *
     * @param actor      当前主体
     * @param logId      记录 ID
     * @param visibility 目标可见性
     * @return 更新后的记录
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkLogEntity updateVisibility(ActorContext actor, UUID logId, String visibility) {
        actor.requireScope(Scope.WORK_WRITE);
        AccessPolicy.requireAssignable(actor, visibility);
        WorkLogEntity entity = requireOwned(actor, logId);
        entity.setVisibility(Visibility.fromValue(visibility).getValue());
        entity.setUpdatedAt(Instant.now());
        workLogMapper.updateById(entity);
        auditRecorder.record(AuditEvent.of(actor.userId(), actor.actorType(), subjectId(actor),
            "work_log.visibility", "work_log", logId));
        return entity;
    }

    /**
     * 标签归一：转小写并去空白，因为多值索引按二进制比较，大小写敏感。
     *
     * @param tags 原始标签
     * @return 归一后的标签列表
     */
    static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream().filter(Objects::nonNull).map(WorkLogService::normalizeTag)
            .filter(tag -> !tag.isEmpty()).distinct().toList();
    }

    private static String normalizeTag(String tag) {
        return tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
    }

    private void validate(WorkLogCommand command) {
        if (command.title() == null || command.title().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "标题不能为空");
        }
        if (command.title().length() > MAX_TITLE_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "标题长度不能超过 " + MAX_TITLE_LENGTH);
        }
        if (length(command.summary()) > MAX_SUMMARY_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "摘要长度不能超过 " + MAX_SUMMARY_LENGTH);
        }
        if (length(command.content()) > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "正文长度不能超过 " + MAX_CONTENT_LENGTH);
        }
        if (command.occurredAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发生时间不能为空");
        }
        if (command.tags() != null) {
            if (command.tags().size() > MAX_TAG_COUNT) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "标签数量不能超过 " + MAX_TAG_COUNT);
            }
            for (String tag : command.tags()) {
                if (tag != null && tag.length() > MAX_TAG_LENGTH) {
                    throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "单个标签长度不能超过 " + MAX_TAG_LENGTH);
                }
            }
        }
        // 提前校验枚举取值，避免把非法值送到数据库后才由 CHECK 约束拒绝
        parseOrNull(command.workType(), "工作类型", WorkType::fromValue);
        parseOrNull(command.visibility(), "可见性", Visibility::fromValue);
    }

    /**
     * 解析枚举取值，把非法值统一转成 {@link ErrorCode#VALIDATION_ERROR}。
     *
     * <p>枚举的 {@code fromValue} 抛 {@link IllegalArgumentException}，直接冒泡会把英文枚举名
     * 暴露给调用方，且新增枚举类型时容易漏改校验分支。
     *
     * @param value  待解析值，为空表示未提供
     * @param label  错误提示中的字段名称
     * @param parser 解析函数
     * @param <T>    枚举类型
     * @return 解析结果，未提供时为 {@code null}
     * @throws BizException 取值非法
     */
    private static <T> T parseOrNull(String value, String label, Function<String, T> parser) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, label + "取值非法：" + value);
        }
    }

    private void requireProjectOwned(ActorContext actor, UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId.toString());
        if (project == null || project.getDeletedAt() != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "关联项目不存在");
        }
        actor.requireOwnership(UUID.fromString(project.getUserId()));
    }

    private Optional<WorkLogEntity> findByIdempotencyKey(ActorContext actor, String key) {
        String value = blankToNull(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workLogMapper.selectOne(new LambdaQueryWrapper<WorkLogEntity>()
            .eq(WorkLogEntity::getUserId, actor.userId().toString())
            .eq(WorkLogEntity::getIdempotencyKey, value)
            .last("LIMIT 1")));
    }

    private WorkLogEntity requireOwned(ActorContext actor, UUID logId) {
        WorkLogEntity entity = workLogMapper.selectById(logId.toString());
        if (entity == null) {
            throw BizException.notFound("work log");
        }
        actor.requireOwnership(UUID.fromString(entity.getUserId()));
        return entity;
    }

    private void applyVisibilityFilter(LambdaQueryWrapper<WorkLogEntity> wrapper, ActorContext actor) {
        if (actor.isOwner()) {
            return;
        }
        if (actor.agentClientId() != null) {
            wrapper.in(WorkLogEntity::getVisibility,
                List.of(Visibility.AGENT.getValue(), Visibility.PUBLIC.getValue()));
        } else {
            wrapper.eq(WorkLogEntity::getVisibility, Visibility.PUBLIC.getValue());
        }
    }

    private List<WorkLogEntity> filterReadable(ActorContext actor, List<WorkLogEntity> rows) {
        return rows.stream().filter(row -> AccessPolicy.readableBy(actor, row.getVisibility())).toList();
    }

    private int capLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private UUID subjectId(ActorContext actor) {
        return actor.agentClientId() == null ? actor.userId() : actor.agentClientId();
    }
}
