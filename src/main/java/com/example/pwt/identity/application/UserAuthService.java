package com.example.pwt.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.pwt.audit.application.AuditEvent;
import com.example.pwt.audit.application.AuditRecorder;
import com.example.pwt.common.api.ActorType;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.common.util.Uuids;
import com.example.pwt.identity.domain.UserStatus;
import com.example.pwt.identity.infrastructure.UserEntity;
import com.example.pwt.identity.infrastructure.UserMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Owner 账号与登录服务。
 *
 * <p>MVP 由 PWT 自身保管口令（BCrypt）；若接入统一身份体系，
 * 改为外部 subject 映射并停止写入 {@code password_hash}（设计文档 8.4 第 1 条）。
 *
 * @author pwt
 */
@Service
public class UserAuthService {

    private static final Logger log = LoggerFactory.getLogger(UserAuthService.class);

    /** 登录失败时统一使用该提示，避免泄露账号是否存在。 */
    private static final String GENERIC_LOGIN_FAILURE = "用户名或口令不正确";

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final OwnerTokenService ownerTokenService;

    private final AuditRecorder auditRecorder;

    /** 账号不存在时用于消耗等量校验时间的占位摘要，避免通过响应时序判断账号存在性。 */
    private final String timingPaddingHash;

    /**
     * 构造服务。
     *
     * @param userMapper        用户 Mapper
     * @param passwordEncoder   口令编码器
     * @param ownerTokenService 令牌服务
     * @param auditRecorder     审计记录器
     */
    public UserAuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           OwnerTokenService ownerTokenService, AuditRecorder auditRecorder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.ownerTokenService = ownerTokenService;
        this.auditRecorder = auditRecorder;
        this.timingPaddingHash = passwordEncoder.encode("pwt-timing-padding");
    }

    /**
     * 创建用户。
     *
     * @param username     登录名，全局唯一
     * @param displayName  展示名称
     * @param rawPassword  明文口令
     * @param email        邮箱，可为空
     * @return 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public UUID register(String username, String displayName, String rawPassword, String email) {
        Assert.hasText(username, "username must not be blank");
        Assert.hasText(displayName, "displayName must not be blank");
        Assert.hasText(rawPassword, "password must not be blank");
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getUsername, username.trim()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "用户名已存在");
        }
        UserEntity entity = new UserEntity();
        entity.setId(Uuids.randomUuidv7String());
        entity.setUsername(username.trim());
        entity.setDisplayName(displayName.trim());
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        entity.setEmail(email == null || email.isBlank() ? null : email.trim());
        entity.setStatus(UserStatus.ACTIVE.getValue());
        entity.setCreatedAt(Instant.now());
        userMapper.insert(entity);
        UUID userId = UUID.fromString(entity.getId());
        auditRecorder.record(AuditEvent.of(userId, ActorType.OWNER, userId, "user.register", "user", userId));
        return userId;
    }

    /**
     * 登录并签发访问令牌。
     *
     * @param username    登录名
     * @param rawPassword 明文口令
     * @return 用户 ID、令牌与过期时间
     * @throws BizException 账号不可用或口令错误
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResult login(String username, String rawPassword) {
        UserEntity entity = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getUsername, username == null ? "" : username.trim())
            .isNull(UserEntity::getDeletedAt)
            .last("LIMIT 1"));
        if (entity == null) {
            // 账号不存在时同样执行一次哈希比对，缩小口令猜测的时序差异
            passwordEncoder.matches(rawPassword, timingPaddingHash);
            throw new BizException(ErrorCode.UNAUTHENTICATED, GENERIC_LOGIN_FAILURE);
        }
        if (!UserStatus.ACTIVE.getValue().equals(entity.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号当前不可用：" + entity.getStatus());
        }
        if (!passwordEncoder.matches(rawPassword, entity.getPasswordHash())) {
            log.info("登录口令错误, username={}", username);
            throw new BizException(ErrorCode.UNAUTHENTICATED, GENERIC_LOGIN_FAILURE);
        }
        UUID userId = UUID.fromString(entity.getId());
        auditRecorder.record(AuditEvent.of(userId, ActorType.OWNER, userId, "user.login", "user", userId));
        return new LoginResult(userId, ownerTokenService.issue(userId));
    }

    /**
     * 登录结果。
     *
     * @param userId 用户 ID
     * @param token  签发的令牌
     * @author pwt
     */
    public record LoginResult(UUID userId, OwnerTokenService.IssuedToken token) {
    }
}
