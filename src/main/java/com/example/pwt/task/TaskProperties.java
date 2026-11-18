package com.example.pwt.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步任务 Worker 配置（设计文档 17.2、18.2）。
 *
 * @param enabled          是否启动轮询 Worker；单实例默认开启
 * @param pollInterval     轮询间隔
 * @param batchSize        单次领取任务数
 * @param leaseDuration    任务租约时长，必须大于单任务最长正常执行时间，否则会造成重复执行
 * @param recoveryInterval 租约回收周期
 * @param backoffBase      重试退避基数，第 n 次重试等待 {@code backoffBase * 2^(n-1)}
 * @param maxRetryAttempts 单次运行内的最大重试次数（对应任务表 max_retries 默认值）
 * @author pwt
 */
@ConfigurationProperties(prefix = "pwt.task")
public record TaskProperties(
    boolean enabled,
    Duration pollInterval,
    int batchSize,
    Duration leaseDuration,
    Duration recoveryInterval,
    Duration backoffBase,
    int maxRetryAttempts) {

    /**
     * 补全未显式配置的默认值。
     */
    public TaskProperties {
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(5);
        }
        if (batchSize <= 0) {
            batchSize = 10;
        }
        if (leaseDuration == null) {
            leaseDuration = Duration.ofMinutes(5);
        }
        if (recoveryInterval == null) {
            recoveryInterval = Duration.ofMinutes(1);
        }
        if (backoffBase == null) {
            backoffBase = Duration.ofSeconds(30);
        }
        if (maxRetryAttempts <= 0) {
            maxRetryAttempts = 5;
        }
    }
}
