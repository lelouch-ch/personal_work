package com.example.pwt.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.identity.application.UserAuthService;
import com.example.pwt.identity.infrastructure.UserEntity;
import com.example.pwt.identity.infrastructure.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 空库引导：创建首个 Owner 账号。
 *
 * <p>只在用户表为空且配置了口令时执行，重复启动不会产生副作用。
 *
 * @author pwt
 */
@Component
public class OwnerBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrapRunner.class);

    private final BootstrapProperties properties;

    private final UserMapper userMapper;

    private final UserAuthService userAuthService;

    /**
     * 构造引导执行器。
     *
     * @param properties      引导配置
     * @param userMapper      用户 Mapper
     * @param userAuthService 用户服务
     */
    public OwnerBootstrapRunner(BootstrapProperties properties, UserMapper userMapper,
                                UserAuthService userAuthService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.userAuthService = userAuthService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Owner 引导已关闭, 跳过");
            return;
        }
        Long total = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>().isNull(UserEntity::getDeletedAt));
        if (total != null && total > 0) {
            log.info("用户表已有 {} 条记录, 跳过 Owner 引导", total);
            return;
        }
        if (properties.password() == null || properties.password().isBlank()) {
            log.warn("用户表为空且未配置 PWT_BOOTSTRAP_PASSWORD, 无法登录; 请设置初始口令后重启");
            return;
        }
        userAuthService.register(properties.username(), properties.displayName(), properties.password(), null);
        log.info("已创建初始 Owner 账号: {}", properties.username());
    }
}
