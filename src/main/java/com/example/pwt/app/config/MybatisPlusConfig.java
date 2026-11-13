package com.example.pwt.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.identity.domain.ActorContextHolder;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>MySQL 不提供行级安全，用户隔离由 {@link TenantLineInnerInterceptor} 以 user_id 为租户列自动追加条件，
 * 这是设计文档 14.3 补偿措施的第 2 项；第 1 项（Repository 强制带 user_id）由代码评审与测试保证。
 *
 * @author pwt
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 不追加 user_id 条件的表。
     *
     * <p>pt_user 无 user_id 列；两张日志表按设计文档 14.3 允许在缺少主体上下文时写入，
     * 且管理员审计查询需要跨用户可见，因此由业务层显式控制条件。
     */
    private static final Set<String> TENANT_IGNORE_TABLES = Set.of("pt_user", "pt_audit_log", "pt_context_access_log");

    /**
     * 注册插件；分页插件必须放在多租户插件之后，否则 count 语句缺少租户条件。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new PwtTenantLineHandler()));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 以当前认证主体的 user_id 作为租户标识。
     *
     * @author pwt
     */
    static class PwtTenantLineHandler implements TenantLineHandler {

        @Override
        public Expression getTenantId() {
            ActorContext context = ActorContextHolder.require();
            return new StringValue(context.userId().toString());
        }

        @Override
        public String getTenantIdColumn() {
            return "user_id";
        }

        @Override
        public boolean ignoreTable(String tableName) {
            if (TENANT_IGNORE_TABLES.contains(tableName.toLowerCase())) {
                return true;
            }
            // 登录、启动自检等尚无主体上下文的场景不追加条件，避免误报未认证
            return ActorContextHolder.get() == null;
        }
    }
}
