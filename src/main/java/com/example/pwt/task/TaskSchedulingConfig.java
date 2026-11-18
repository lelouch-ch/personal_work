package com.example.pwt.task;

import com.example.pwt.task.application.AsyncTaskWorker;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 以编程方式注册 Worker 的轮询与租约回收周期。
 *
 * <p>不用 {@code @Scheduled(fixedDelayString = "${pwt.task.poll-interval}")}：该属性只接受
 * 毫秒数或 ISO-8601（{@code PT5S}），而配置项按 Spring Boot 的 Duration  relaxed 绑定写成
 * {@code 5s}，两种格式不一致会在启动时才报错。直接复用 {@link TaskProperties} 已解析好的
 * {@link java.time.Duration}，配置只有一种写法。
 *
 * <p>{@code pwt.task.enabled=false} 时不注册周期任务，测试与只跑 REST 的实例都不会空转。
 *
 * @author pwt
 */
@Configuration
public class TaskSchedulingConfig implements SchedulingConfigurer {

    private final TaskProperties properties;

    private final AsyncTaskWorker worker;

    /**
     * 构造配置。
     *
     * @param properties 任务配置
     * @param worker     Worker
     */
    public TaskSchedulingConfig(TaskProperties properties, AsyncTaskWorker worker) {
        this.properties = properties;
        this.worker = worker;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            return;
        }
        // 首次延迟与轮询间隔取同值：给 Flyway 迁移与 Owner 引导留出时间，避免启动瞬间空跑一轮
        registrar.addFixedDelayTask(new IntervalTask(worker::poll, properties.pollInterval(),
            properties.pollInterval()));
        registrar.addFixedDelayTask(new IntervalTask(worker::recover, properties.recoveryInterval(),
            properties.recoveryInterval()));
    }
}
