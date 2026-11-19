package com.example.pwt.skill.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.PageQuery;
import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.skill.domain.SkillStatus;
import com.example.pwt.skill.infrastructure.SkillEntity;
import com.example.pwt.skill.infrastructure.SkillMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * Skill 服务的版本与权限规则测试（设计文档 5.2、10.2、15.2）。
 *
 * <p>不依赖数据库：判定都在触达持久层之前完成或被 mock 隔离，
 * 唯一键与全文索引层面的行为由 {@code MysqlDdlMigrationTest} 覆盖。
 *
 * @author pwt
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private AuditRecorder auditRecorder;

    private SkillService service;

    private ActorContext owner;

    private ActorContext agent;

    @BeforeEach
    void setUp() {
        service = new SkillService(skillMapper, auditRecorder);
        owner = ActorContext.owner(USER_ID);
        agent = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.SKILL_READ, Scope.CONTEXT_BUILD));
        lenient().when(skillMapper.insert(any(SkillEntity.class))).thenReturn(1);
        lenient().when(skillMapper.updateById(any(SkillEntity.class))).thenReturn(1);
        lenient().when(skillMapper.selectMaxVersion(anyString(), anyString())).thenReturn(0);
    }

    @Test
    void agentMustNotWriteSkills() {
        // 10.2：Skill 接口全部标为 Owner，Agent 只消费流程，不能自行增改
        assertThatThrownBy(() -> service.create(agent, command("code-review", "代码评审")))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.update(agent, UUID.randomUUID(), command("code-review", "代码评审")))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.publish(agent, UUID.randomUUID()))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.archive(agent, UUID.randomUUID()))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.list(agent, null, PageQuery.of(1, 20)))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(skillMapper, never()).insert(any(SkillEntity.class));
    }

    @Test
    void createShouldStartAsDraftPrivateVersionOne() {
        SkillEntity saved = service.create(owner, command("code-review", "代码评审"));

        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(SkillStatus.DRAFT.getValue());
        assertThat(saved.getVisibility()).isEqualTo(Visibility.PRIVATE.getValue());
        assertThat(saved.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createShouldTakeNextVersionAfterTheHighestUsedOne() {
        when(skillMapper.selectMaxVersion(USER_ID.toString(), "code-review")).thenReturn(3);

        SkillEntity saved = service.create(owner, command("code-review", "代码评审"));

        // 版本号不过滤状态：归档版本也占唯一键的位置
        assertThat(saved.getVersion()).isEqualTo(4);
    }

    @Test
    void ownerMayCreatePublicSkill() {
        assertThatCode(() -> service.create(owner,
            command("code-review", "代码评审", Visibility.PUBLIC.getValue())))
            .doesNotThrowAnyException();
    }

    @Test
    void invalidSlugsShouldBeRejected() {
        // 中文名按 ASCII 推导只剩连字符，不如报错让 Owner 起一个稳定标识
        for (String slug : List.of("代码评审", "Code Review", "code_review", "-code", "code-", "-", "", "   ")) {
            assertThatThrownBy(() -> service.create(owner, command(slug, "代码评审")))
                .as("slug=%s 应被拒绝", slug)
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);
        }
        verify(skillMapper, never()).insert(any(SkillEntity.class));
    }

    @Test
    void slugShouldBeNormalizedInsteadOfRejectedForCaseAndRepeatedHyphens() {
        SkillEntity saved = service.create(owner, command("  Hand--Off ", "交接清单"));

        assertThat(saved.getSlug()).isEqualTo("hand-off");
    }

    @Test
    void updatingDraftShouldStayInPlace() {
        SkillEntity draft = existing(SkillStatus.DRAFT, Visibility.PRIVATE);
        when(skillMapper.selectById(draft.getId())).thenReturn(draft);

        SkillEntity result = service.update(owner, UUID.fromString(draft.getId()),
            command("code-review", "代码评审清单"));

        assertThat(result.getId()).isEqualTo(draft.getId());
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getContentMd()).isEqualTo("代码评审清单");
        verify(skillMapper).updateById(draft);
        verify(skillMapper, never()).insert(any(SkillEntity.class));
    }

    @Test
    void updatingPublishedSkillShouldCreateNewDraftAndKeepOriginal() {
        SkillEntity published = existing(SkillStatus.PUBLISHED, Visibility.AGENT);
        when(skillMapper.selectById(published.getId())).thenReturn(published);
        when(skillMapper.selectMaxVersion(USER_ID.toString(), "code-review")).thenReturn(1);

        SkillEntity result = service.update(owner, UUID.fromString(published.getId()),
            command("code-review", "改版后的评审清单"));

        // 已发布内容不能被就地篡改：15.2 的「发布前由 Owner 审核」依赖这个前提
        assertThat(published.getStatus()).isEqualTo(SkillStatus.PUBLISHED.getValue());
        assertThat(published.getContentMd()).isEqualTo("原始评审清单");
        assertThat(result.getId()).isNotEqualTo(published.getId());
        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(SkillStatus.DRAFT.getValue());
        assertThat(result.getSlug()).isEqualTo("code-review");
        verify(skillMapper).insert(result);
        verify(skillMapper, never()).updateById(published);
    }

    @Test
    void publishShouldArchiveOtherPublishedVersionsOfSameSlug() {
        SkillEntity draft = existing(SkillStatus.DRAFT, Visibility.AGENT);
        draft.setId(UUID.randomUUID().toString());
        draft.setVersion(2);
        SkillEntity previous = existing(SkillStatus.PUBLISHED, Visibility.AGENT);
        previous.setVersion(1);
        when(skillMapper.selectById(draft.getId())).thenReturn(draft);
        when(skillMapper.selectList(any())).thenReturn(List.of(previous));

        SkillEntity result = service.publish(owner, UUID.fromString(draft.getId()));

        assertThat(result.getStatus()).isEqualTo(SkillStatus.PUBLISHED.getValue());
        assertThat(previous.getStatus()).isEqualTo(SkillStatus.ARCHIVED.getValue());
        verify(skillMapper).updateById(previous);
        verify(skillMapper).updateById(result);
    }

    @Test
    void archivedSkillMustNotBePublishedAgain() {
        // 归档表达「这套做法不再适用」，重新启用要显式新建版本，避免误点让过时流程回到上下文
        SkillEntity archived = existing(SkillStatus.ARCHIVED, Visibility.AGENT);
        when(skillMapper.selectById(archived.getId())).thenReturn(archived);

        assertThatThrownBy(() -> service.publish(owner, UUID.fromString(archived.getId())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR)
            .hasMessageContaining("只有草稿可以发布");
    }

    @Test
    void publishedSkillMustNotBePublishedAgain() {
        SkillEntity published = existing(SkillStatus.PUBLISHED, Visibility.AGENT);
        when(skillMapper.selectById(published.getId())).thenReturn(published);

        assertThatThrownBy(() -> service.publish(owner, UUID.fromString(published.getId())))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只有草稿可以发布");
        verify(skillMapper, never()).updateById(any(SkillEntity.class));
    }

    @Test
    void archivedSkillMustNotBeUpdatedInPlace() {
        SkillEntity archived = existing(SkillStatus.ARCHIVED, Visibility.AGENT);
        when(skillMapper.selectById(archived.getId())).thenReturn(archived);

        assertThatThrownBy(() -> service.update(owner, UUID.fromString(archived.getId()),
            command("code-review", "改一点内容")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已归档");
        verify(skillMapper, never()).updateById(any(SkillEntity.class));
    }

    @Test
    void archivingTwiceShouldBeIdempotent() {
        SkillEntity archived = existing(SkillStatus.ARCHIVED, Visibility.AGENT);
        when(skillMapper.selectById(archived.getId())).thenReturn(archived);

        assertThat(service.archive(owner, UUID.fromString(archived.getId())).getStatus())
            .isEqualTo(SkillStatus.ARCHIVED.getValue());
        verify(skillMapper, never()).updateById(any(SkillEntity.class));
    }

    @Test
    void foreignSkillShouldReadAsNotFound() {
        SkillEntity other = existing(SkillStatus.PUBLISHED, Visibility.PUBLIC);
        other.setUserId(UUID.randomUUID().toString());
        when(skillMapper.selectById(other.getId())).thenReturn(other);

        // 跨用户统一按不存在处理，不泄露资源存在性（14.3）
        assertThatThrownBy(() -> service.read(owner, UUID.fromString(other.getId())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service.publish(owner, UUID.fromString(other.getId())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void agentShouldNotReadDraftButMayReadPublishedAgentSkill() {
        SkillEntity draft = existing(SkillStatus.DRAFT, Visibility.AGENT);
        when(skillMapper.selectById(draft.getId())).thenReturn(draft);
        assertThatThrownBy(() -> service.read(agent, UUID.fromString(draft.getId())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        SkillEntity published = existing(SkillStatus.PUBLISHED, Visibility.AGENT);
        when(skillMapper.selectById(published.getId())).thenReturn(published);
        assertThat(service.read(agent, UUID.fromString(published.getId())).getId())
            .isEqualTo(published.getId());

        SkillEntity privateSkill = existing(SkillStatus.PUBLISHED, Visibility.PRIVATE);
        when(skillMapper.selectById(privateSkill.getId())).thenReturn(privateSkill);
        assertThatThrownBy(() -> service.read(agent, UUID.fromString(privateSkill.getId())))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void readingSkillShouldRequireScope() {
        ActorContext reader = ActorContext.agent(USER_ID, AGENT_ID, Set.of(Scope.CONTEXT_BUILD));

        assertThatThrownBy(() -> service.read(reader, UUID.randomUUID()))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCOPE_REQUIRED);
        verify(skillMapper, never()).selectById(any());
    }

    @Test
    void blankOrOverlongFieldsShouldBeRejected() {
        assertThatThrownBy(() -> service.create(owner, command("code-review", "  ")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("contentMd");
        assertThatThrownBy(() -> service.create(owner, command("code-review", "长".repeat(30001))))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("30000");
        assertThatThrownBy(() -> service.create(owner,
            new SkillCommand("code-review", "  ", null, "内容", null, null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("name");
        assertThatThrownBy(() -> service.create(owner,
            new SkillCommand("code-review", "代码评审", "说".repeat(2001), "内容", null, null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("description");
        verify(skillMapper, never()).insert(any(SkillEntity.class));
    }

    @Test
    void sourceMemoryIdsShouldBeValidatedAndDeduplicated() {
        assertThatThrownBy(() -> service.create(owner,
            new SkillCommand("code-review", "代码评审", null, "内容", null, List.of("not-a-uuid"))))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("sourceMemoryIds");

        UUID memoryId = UUID.randomUUID();
        // 允许重复与空白项：Owner 从网页勾选来源记忆时可能带入同一条两次
        SkillEntity saved = service.create(owner, new SkillCommand("code-review", "代码评审", null, "内容", null,
            Arrays.asList(memoryId.toString(), " " + memoryId + " ", "", null)));

        assertThat(saved.getSourceMemoryIds()).containsExactly(memoryId.toString());
    }

    @Test
    void concurrentSameVersionShouldSurfaceAsConflict() {
        when(skillMapper.insert(any(SkillEntity.class)))
            .thenThrow(new DuplicateKeyException("uq_skill_user_slug_version"));

        assertThatThrownBy(() -> service.create(owner, command("code-review", "代码评审")))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void listShouldReturnEmptyPageForOwner() {
        when(skillMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        assertThat(service.list(owner, null, PageQuery.of(1, 20)).items()).isEmpty();
        verify(skillMapper).selectPage(any(), any());
    }

    private static SkillCommand command(String slug, String contentMd) {
        return command(slug, contentMd, null);
    }

    private static SkillCommand command(String slug, String contentMd, String visibility) {
        return new SkillCommand(slug, "代码评审", "评审前逐项核对", contentMd, visibility, null);
    }

    private static SkillEntity existing(SkillStatus status, Visibility visibility) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(USER_ID.toString());
        entity.setSlug("code-review");
        entity.setName("代码评审");
        entity.setDescription("评审前逐项核对");
        entity.setContentMd("原始评审清单");
        entity.setVersion(1);
        entity.setStatus(status.getValue());
        entity.setVisibility(visibility.getValue());
        entity.setSourceMemoryIds(List.of());
        return entity;
    }
}
