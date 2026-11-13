package com.example.pwt.common.api;

/**
 * Agent 凭证 scope 常量（设计文档 14.2）。
 *
 * <p>Owner 网站会话使用用户角色权限，Agent Token 使用 scope，两套判定不得混用。
 *
 * @author pwt
 */
public final class Scope {

    /** 读取个人画像与简历。 */
    public static final String PROFILE_READ = "profile:read";
    /** 读取工作记录。 */
    public static final String WORK_READ = "work:read";
    /** 写入工作记录。 */
    public static final String WORK_WRITE = "work:write";
    /** 读取已批准记忆。 */
    public static final String MEMORY_READ = "memory:read";
    /** 提交记忆候选。 */
    public static final String MEMORY_PROPOSE = "memory:propose";
    /** 构建上下文包。 */
    public static final String CONTEXT_BUILD = "context:build";
    /** 读取 Skill。 */
    public static final String SKILL_READ = "skill:read";
    /** 生成报告。 */
    public static final String REPORT_GENERATE = "report:generate";

    /**
     * 全部合法 scope，用于签发凭证时的取值校验。
     */
    public static final java.util.Set<String> ALL = java.util.Set.of(
        PROFILE_READ, WORK_READ, WORK_WRITE, MEMORY_READ, MEMORY_PROPOSE,
        CONTEXT_BUILD, SKILL_READ, REPORT_GENERATE);

    private Scope() {
    }
}
