package com.example.pwt.task.infrastructure;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Update;

/**
 * 异步任务表 Mapper。
 *
 * <p>领取流程按设计文档 17.2 拆为同一事务内的两步：先 {@code FOR UPDATE SKIP LOCKED} 加锁选取，
 * 再按 ID 置为 {@code running}。MySQL 不支持 {@code UPDATE ... RETURNING}，无法一步完成。
 *
 * <p>时间比较统一用 {@code UTC_TIMESTAMP(3)}，与 {@code datetime(3)} 的 UTC 存储约定一致，
 * 不用 {@code NOW()}（受会话时区影响）。
 *
 * <p>领取与租约回收必须用 {@link InterceptorIgnore} 关掉租户插件：这两个动作天然跨用户，
 * 且实测（MySQL 9.3.0 + MyBatis-Plus 3.5.6）发现插件即便因“当前无主体上下文”而不追加
 * {@code user_id} 条件，仍会重新解析并拼装 SQL，把 {@code FOR UPDATE SKIP LOCKED} 挪到
 * {@code ORDER BY}/{@code LIMIT} 之前，生成
 * {@code ... FOR UPDATE SKIP LOCKED ORDER BY created_at LIMIT ?} 这样的非法语法，
 * 使 Worker 每轮领取都抛异常、任务永远停在 pending。单测用 mock Mapper 抓不到，
 * 只有真实 MySQL 才暴露。
 *
 * <p>成功/失败回写不关租户插件：它们在处理阶段执行，此时已绑定该任务所属用户的系统主体，
 * 追加 {@code user_id = ?} 正好是一道额外的归属保险（设计文档 14.3）。
 *
 * <p>读取 {@code payload} 的语句必须显式声明 JSON 列的类型处理器：实体上的
 * {@code @TableName(autoResultMap = true)} 只对 MyBatis-Plus 自动生成的语句生效，
 * 注解 SQL 不继承它，JSON 列因此不走 {@link JacksonTypeHandler}。
 * 实测该遗漏使 Worker 读到的 payload 恒为 null，提取任务全部失败重试。
 * 其余列继续由 {@code map-underscore-to-camel-case} 自动映射，这里只声明 JSON 列。
 *
 * @author pwt
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTaskEntity> {

    /** JSON 列的显式映射标识，供所有读取 payload 的语句复用。 */
    String PAYLOAD_RESULTS = "asyncTaskPayloadResults";

    /**
     * 加锁领取到期的待执行任务。
     *
     * @param limit 本次最多领取条数
     * @return 已加锁的任务，按创建时间升序
     */
    @InterceptorIgnore(tenantLine = "true")
    @Results(id = PAYLOAD_RESULTS, value = {
        @Result(column = "payload", property = "payload", typeHandler = JacksonTypeHandler.class)
    })
    @Select("""
        SELECT id, user_id, task_type, dedup_ref, payload, status, retry_count, max_retries,
               next_retry_at, locked_at, locked_by, last_error, created_at, updated_at
        FROM pt_async_task
        WHERE status = 'pending' AND next_retry_at <= UTC_TIMESTAMP(3)
        ORDER BY created_at
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<AsyncTaskEntity> selectClaimable(@Param("limit") int limit);

    /**
     * 把已加锁选取的任务置为 running。
     *
     * @param ids      任务 ID 列表，调用方保证非空
     * @param workerId 领取者标识
     * @return 更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        <script>
        UPDATE pt_async_task
        SET status = 'running', locked_at = UTC_TIMESTAMP(3), locked_by = #{workerId},
            updated_at = UTC_TIMESTAMP(3)
        WHERE id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int markRunning(@Param("ids") List<String> ids, @Param("workerId") String workerId);

    /**
     * 标记任务成功。
     *
     * @param id 任务 ID
     * @return 更新行数
     */
    @Update("UPDATE pt_async_task SET status = 'succeeded', last_error = NULL,"
        + " updated_at = UTC_TIMESTAMP(3) WHERE id = #{id} AND status = 'running'")
    int markSucceeded(@Param("id") String id);

    /**
     * 记录一次失败并安排退避重试。
     *
     * <p>重试耗尽时置为 failed，否则回到 pending 并推后 {@code next_retry_at}。
     * 只有仍持有 running 状态的任务可被更新，避免租约已被回收的任务被旧 worker 覆盖。
     *
     * @param id          任务 ID
     * @param error       脱敏后的错误信息
     * @param nextRetryAt 下次可领取时间
     * @param exhausted   是否已达最大重试次数
     * @return 更新行数
     */
    @Update("""
        UPDATE pt_async_task
        SET status = CASE WHEN #{exhausted} THEN 'failed' ELSE 'pending' END,
            retry_count = retry_count + 1,
            next_retry_at = #{nextRetryAt},
            locked_at = NULL,
            locked_by = NULL,
            last_error = #{error},
            updated_at = UTC_TIMESTAMP(3)
        WHERE id = #{id} AND status = 'running'
        """)
    int markRetryOrFailed(@Param("id") String id, @Param("error") String error,
                          @Param("nextRetryAt") Instant nextRetryAt, @Param("exhausted") boolean exhausted);

    /**
     * 回收租约过期但仍停在 running 的任务。
     *
     * <p>worker 崩溃会留下永久占用的任务（设计文档 17.2 崩溃恢复）。重试次数已达上限的直接判 failed，
     * 避免无限重放。
     *
     * @param leaseSeconds 租约时长，必须大于单任务最长正常执行时间
     * @return 回收的任务数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE pt_async_task
        SET status = CASE WHEN retry_count >= max_retries THEN 'failed' ELSE 'pending' END,
            next_retry_at = CASE WHEN retry_count >= max_retries THEN next_retry_at ELSE UTC_TIMESTAMP(3) END,
            retry_count = retry_count + 1,
            locked_at = NULL,
            locked_by = NULL,
            updated_at = UTC_TIMESTAMP(3)
        WHERE status = 'running'
          AND locked_at < UTC_TIMESTAMP(3) - INTERVAL #{leaseSeconds} SECOND
        """)
    int recoverExpiredLeases(@Param("leaseSeconds") long leaseSeconds);

    /**
     * 按去重键查找既有任务，用于写入前的幂等回读。
     *
     * @param userId   所属用户
     * @param taskType 任务类型
     * @param dedupRef 去重引用
     * @return 既有任务，不存在时为 {@code null}
     */
    @ResultMap(PAYLOAD_RESULTS)
    @Select("SELECT id, user_id, task_type, dedup_ref, payload, status, retry_count, max_retries,"
        + " next_retry_at, locked_at, locked_by, last_error, created_at, updated_at"
        + " FROM pt_async_task WHERE user_id = #{userId} AND task_type = #{taskType}"
        + " AND dedup_ref = #{dedupRef} LIMIT 1")
    AsyncTaskEntity selectByDedupKey(@Param("userId") String userId, @Param("taskType") String taskType,
                                     @Param("dedupRef") String dedupRef);

    /**
     * 统计各状态任务数，供工作台指标使用（设计文档 16.2）。
     *
     * @param userId 所属用户
     * @param status 状态
     * @return 任务数
     */
    @Select("SELECT COUNT(*) FROM pt_async_task WHERE user_id = #{userId} AND status = #{status}")
    long countByStatus(@Param("userId") String userId, @Param("status") String status);
}
