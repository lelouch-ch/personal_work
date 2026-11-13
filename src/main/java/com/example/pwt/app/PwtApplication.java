package com.example.pwt.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Personal Work Twin 单实例启动类。
 *
 * <p>MVP 采用模块化单体（设计文档 ADR-001），REST、MCP、异步 Worker 同进程部署。
 *
 * @author pwt
 */
@SpringBootApplication(scanBasePackages = "com.example.pwt")
@ConfigurationPropertiesScan("com.example.pwt")
@MapperScan("com.example.pwt.**.infrastructure")
@EnableScheduling
public class PwtApplication {

    /**
     * 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PwtApplication.class, args);
    }
}
