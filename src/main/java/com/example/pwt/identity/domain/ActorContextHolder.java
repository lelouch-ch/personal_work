package com.example.pwt.identity.domain;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;

/**
 * 当前请求的已认证主体持有器。
 *
 * <p>MySQL 没有行级安全，user_id 隔离由本上下文驱动多租户拦截器自动追加条件（设计文档 14.3）。
 * 必须在请求结束时清理，避免线程复用导致用户上下文串线。
 *
 * @author pwt
 */
public final class ActorContextHolder {

    private static final ThreadLocal<ActorContext> CURRENT = new ThreadLocal<>();

    private ActorContextHolder() {
    }

    /**
     * 绑定当前主体。
     *
     * @param context 主体上下文
     */
    public static void set(ActorContext context) {
        CURRENT.set(context);
    }

    /**
     * 获取当前主体。
     *
     * @return 主体上下文，未认证时为 {@code null}
     */
    public static ActorContext get() {
        return CURRENT.get();
    }

    /**
     * 获取当前主体，缺失时抛出未认证异常。
     *
     * @return 主体上下文
     * @throws BizException 未认证
     */
    public static ActorContext require() {
        ActorContext context = CURRENT.get();
        if (context == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "No authenticated actor in current context");
        }
        return context;
    }

    /**
     * 清理线程变量。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
