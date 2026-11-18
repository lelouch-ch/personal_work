package com.example.pwt.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.common.api.Scope;
import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 可见性判定矩阵测试（设计文档 5.3、9.6，ADR-023）。
 *
 * @author pwt
 */
class AccessPolicyTest {

    private final ActorContext owner = ActorContext.owner(UUID.randomUUID());

    private final ActorContext agent = ActorContext.agent(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(Scope.PROFILE_READ));

    private final ActorContext system = ActorContext.system(UUID.randomUUID());

    @Test
    void ownerShouldReadEveryVisibility() {
        for (Visibility visibility : Visibility.values()) {
            assertThat(AccessPolicy.readableBy(owner, visibility.getValue()))
                .as("Owner 应可读 %s", visibility)
                .isTrue();
        }
    }

    @Test
    void agentShouldNotReadPrivate() {
        assertThat(AccessPolicy.readableBy(agent, Visibility.PRIVATE.getValue()))
            .as("private 对 Agent 不可见，否则 agent 档没有存在必要")
            .isFalse();
    }

    @Test
    void agentShouldReadAgentAndPublic() {
        assertThat(AccessPolicy.readableBy(agent, Visibility.AGENT.getValue())).isTrue();
        assertThat(AccessPolicy.readableBy(agent, Visibility.PUBLIC.getValue())).isTrue();
    }

    @Test
    void anonymousShouldReadOnlyPublic() {
        assertThat(AccessPolicy.readableBy(null, Visibility.PRIVATE.getValue())).isFalse();
        assertThat(AccessPolicy.readableBy(null, Visibility.AGENT.getValue())).isFalse();
        assertThat(AccessPolicy.readableBy(null, Visibility.PUBLIC.getValue())).isTrue();
        assertThat(AccessPolicy.anonymouslyReadable(Visibility.PUBLIC.getValue())).isTrue();
        assertThat(AccessPolicy.anonymouslyReadable(Visibility.AGENT.getValue())).isFalse();
    }

    @Test
    void missingOrNullVisibilityShouldFallBackToPrivate() {
        // 默认必须是 private，不能因为缺值而放宽
        assertThat(AccessPolicy.readableBy(agent, null)).isFalse();
        assertThat(AccessPolicy.readableBy(agent, "")).isFalse();
        assertThat(AccessPolicy.readableBy(owner, null)).isTrue();
        assertThat(AccessPolicy.anonymouslyReadable(null)).isFalse();
    }

    @Test
    void unknownVisibilityShouldBeRejectedNotRelaxed() {
        assertThatThrownBy(() -> AccessPolicy.readableBy(agent, "everybody"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown visibility");
    }

    @Test
    void onlyOwnerShouldAssignPublic() {
        assertThatCode(() -> AccessPolicy.requireAssignable(owner, "public")).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireAssignable(agent, "public"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            // 设计文档 9.6 把该限制归入写入校验，与字段长度等约束同级，统一回 400
            .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> AccessPolicy.requireAssignable(null, "public"))
            .isInstanceOf(BizException.class);
        assertThatCode(() -> AccessPolicy.requireAssignable(agent, "agent")).doesNotThrowAnyException();
        // 缺省值不触发校验，由列默认值兜底
        assertThatCode(() -> AccessPolicy.requireAssignable(agent, null)).doesNotThrowAnyException();
    }

    @Test
    void sqlConditionShouldWidenWithTrust() {
        // Owner 不过滤，Agent 两档，匿名只留 public，形成严格递增的阶梯
        assertThat(AccessPolicy.visibilityCondition(owner)).isEqualTo("1 = 1");
        assertThat(AccessPolicy.visibilityCondition(agent)).isEqualTo("visibility IN ('agent', 'public')");
        assertThat(AccessPolicy.visibilityCondition(null)).isEqualTo("visibility = 'public'");
    }

    @Test
    void systemActorShouldReadPrivateOfItsOwnUser() {
        // ADR-024：Worker 替用户处理自有数据，必须能读到 private 工作记录，
        // 否则“工作记录 → 候选记忆”无法自动进行；隔离由 user_id 而非可见性承担
        for (Visibility visibility : Visibility.values()) {
            assertThat(AccessPolicy.readableBy(system, visibility.getValue()))
                .as("系统主体应可读 %s", visibility).isTrue();
        }
        assertThat(AccessPolicy.visibilityCondition(system)).isEqualTo("1 = 1");
    }

    @Test
    void systemActorMustNotPublishPublic() {
        // 对外发布权只属于 Owner：Worker 不得把数据提升到 public
        assertThatThrownBy(() -> AccessPolicy.requireAssignable(system, "public"))
            .isInstanceOf(BizException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);
        assertThatCode(() -> AccessPolicy.requireAssignable(system, "agent")).doesNotThrowAnyException();
    }

    @Test
    void systemActorShouldBypassScopeChecks() {
        assertThatCode(() -> system.requireScope(Scope.MEMORY_PROPOSE)).doesNotThrowAnyException();
        assertThat(system.hasScope("anything")).isTrue();
        assertThat(system.isSystem()).isTrue();
        assertThat(system.isOwner()).isFalse();
    }

    @Test
    void visibilityValuesShouldMatchDdlCheckConstraint() {
        // 与所有业务表 visibility 列的 CHECK 取值保持一致，避免枚举与库约束漂移
        assertThat(Visibility.values()).hasSize(3);
        assertThat(Visibility.values()).extracting(Visibility::getValue)
            .containsExactly("private", "agent", "public");
    }
}
