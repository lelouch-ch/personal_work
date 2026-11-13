package com.example.pwt.identity.domain;

import com.example.pwt.common.api.Visibility;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;

/**
 * 可见性判定策略：把 {@link Visibility} 与 {@link ActorContext} 的交叉规则集中到一处。
 *
 * <p>设计文档 5.3 只给出三档语义、9.6 只约束了写入，二者叠加后的完整矩阵未定义，
 * 各处自行判断容易漂移。这里固化为严格递增的三档阶梯（ADR-023）：
 *
 * <ul>
 *   <li>{@code private}：仅 Owner 可读，Agent 与匿名均不可读。</li>
 *   <li>{@code agent}：Owner 可读；Agent 需同时具备相应 scope 可读；匿名不可读。</li>
 *   <li>{@code public}：所有人可读。</li>
 * </ul>
 *
 * <p>让 {@code private} 对 Agent 也不可见的原因：若 {@code private} 已允许被授权 Agent 读取，
 * {@code agent} 档就没有存在必要。Agent 可见的数据必须由 Owner 显式提到 {@code agent} 档，
 * 与 5.3「不能因为某条信息出现在公开简历里，就推断同一项目的工作日志也可公开」一致。
 *
 * <p>scope 与 visibility 是两个独立维度：scope 决定“该 Agent 能否碰这类资源”，
 * visibility 决定“该条数据允许给谁看”，两者都通过才可读。
 *
 * @author pwt
 */
public final class AccessPolicy {

    /** 可见性受限时的提示文案（设计文档 5.3、9.6）。 */
    private static final String PUBLIC_ONLY_BY_OWNER =
        "visibility 只能是 private 或 agent，公开内容需由 Owner 在网站显式发布";

    /** Agent 可读的可见性条件，不含 user_id 条件。 */
    private static final String AGENT_READABLE_SQL = "visibility IN ('agent', 'public')";

    /** 匿名可读的可见性条件。 */
    private static final String ANONYMOUS_READABLE_SQL = "visibility = 'public'";

    /** Owner 不做可见性过滤。 */
    private static final String NO_FILTER_SQL = "1 = 1";

    private AccessPolicy() {
    }

    /**
     * 判断主体在通过 scope 校验后，是否还可读取指定可见性的数据。
     *
     * @param actor      已认证主体，为空按匿名处理
     * @param visibility 数据可见性存储值
     * @return true 表示可读
     */
    public static boolean readableBy(ActorContext actor, String visibility) {
        Visibility level = parse(visibility);
        if (level == Visibility.PUBLIC) {
            return true;
        }
        if (actor == null) {
            return false;
        }
        if (actor.isOwner()) {
            return true;
        }
        return level == Visibility.AGENT;
    }

    /**
     * 判断指定可见性是否允许匿名访问。
     *
     * @param visibility 数据可见性存储值
     * @return true 表示匿名可读
     */
    public static boolean anonymouslyReadable(String visibility) {
        return parse(visibility) == Visibility.PUBLIC;
    }

    /**
     * 校验非 Owner 主体提交的可见性是否合法：不得直接发布为 public。
     *
     * @param actor      提交可见性的主体
     * @param visibility 待写入的可见性存储值，可为空
     * @throws BizException 非 Owner 试图写入 public
     */
    public static void requireAssignable(ActorContext actor, String visibility) {
        if (visibility != null && parse(visibility) == Visibility.PUBLIC
            && (actor == null || !actor.isOwner())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, PUBLIC_ONLY_BY_OWNER);
        }
    }

    /**
     * 返回可读数据对应的 SQL 条件片段。不含 user_id 条件，隔离条件必须另行附加。
     *
     * @param actor 已认证主体，可为空表示匿名
     * @return 可拼入 WHERE 的条件
     */
    public static String visibilityCondition(ActorContext actor) {
        if (actor != null && actor.isOwner()) {
            return NO_FILTER_SQL;
        }
        if (actor != null && actor.agentClientId() != null) {
            return AGENT_READABLE_SQL;
        }
        return ANONYMOUS_READABLE_SQL;
    }

    /**
     * 解析存储值，缺省按最严格的 private 处理。
     *
     * @param visibility 存储值，可为空
     * @return 可见性枚举
     */
    private static Visibility parse(String visibility) {
        return Visibility.fromValueOrDefault(visibility, Visibility.PRIVATE);
    }
}
