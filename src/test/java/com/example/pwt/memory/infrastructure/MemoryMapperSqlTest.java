package com.example.pwt.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 检索语句的 SQL 字面量检查（设计文档 8.4 第 19 条）。
 *
 * <p>只读注解值，不连数据库：这条断言针对的是一个只在真实调用时才暴露的写法错误，
 * 用一个方法级断言把它锁死，比每次靠端到端跑出来划算。
 *
 * @author pwt
 */
class MemoryMapperSqlTest {

    /**
     * {@code LIKE ... ESCAPE} 的转义符必须在 SQL 字面量里写成两个反斜杠。
     *
     * <p>Java 文本块中 {@code '\\'} 只产出一个字符 {@code \}，落到 SQL 里是 {@code ESCAPE '\'}：
     * MySQL 会把闭合引号前的反斜杠当作转义，吞掉引号与后面的 {@code #{}} 占位符，
     * 最终报 {@code Parameter index out of range}。端到端跑单字兜底查询时实测到，
     * 必须写成 {@code '\\\\'}。
     *
     * @throws Exception 反射取注解失败
     */
    @Test
    void likeEscapeClauseMustKeepTwoBackslashesInSqlLiteral() throws Exception {
        String sql = selectOf("searchByLike");

        assertThat(sql).contains("ESCAPE '\\\\'");
        assertThat(sql).doesNotContain("ESCAPE '\\' ");
    }

    /**
     * 布尔检索必须用布尔模式，且相关度分数要作为虚拟列返回（11.4 的相关度分项来源）。
     *
     * @throws Exception 反射取注解失败
     */
    @Test
    void booleanRecallMustUseBooleanModeAndReturnRelevance() throws Exception {
        String sql = selectOf("searchBoolean");

        assertThat(sql).contains("IN BOOLEAN MODE");
        assertThat(sql).contains("AS relevance");
        // 未审核记忆不得进入上下文（9.7）
        assertThat(sql).contains("status = 'approved'");
    }

    /**
     * 项目过滤必须保留“项目未知”的记忆，否则全局偏好与约束会在按项目提问时静默消失。
     *
     * @throws Exception 反射取注解失败
     */
    @Test
    void projectFilterMustKeepMemoriesWithUnknownProject() throws Exception {
        assertThat(selectOf("searchBoolean")).contains("project_id = #{projectId} OR project_id IS NULL");
        assertThat(selectOf("searchByLike")).contains("project_id = #{projectId} OR project_id IS NULL");
    }

    /**
     * 检索语句的列清单必须包含 {@code project_id}，否则该字段恒为 null 而项目匹配分项失效。
     *
     * @throws Exception 反射取注解失败
     */
    @Test
    void recallStatementsMustSelectProjectId() throws Exception {
        assertThat(selectOf("searchBoolean")).contains("source_id, project_id, version");
        assertThat(selectOf("searchByLike")).contains("source_id, project_id, version");
    }

    private String selectOf(String methodName) throws Exception {
        for (Method method : MemoryMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertThat(select).as("%s 应有 @Select 注解", methodName).isNotNull();
                return String.join("\n", select.value());
            }
        }
        throw new AssertionError("未找到方法 " + methodName + "，现有方法："
            + List.of(MemoryMapper.class.getDeclaredMethods()).stream().map(Method::getName).toList());
    }
}
