package com.example.pwt.identity.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户持久化对象，映射 {@code pt_user}。
 *
 * <p>主键由应用生成 UUIDv7（设计文档 8.1），不使用数据库自增或默认值。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName("pt_user")
public class UserEntity {

    /** 用户 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 登录名，全局唯一。 */
    private String username;

    /** 邮箱。 */
    private String email;

    /** BCrypt 口令摘要，接入外部认证中心时可为空。 */
    private String passwordHash;

    /** 展示名称。 */
    private String displayName;

    /** 状态：active / disabled / locked。 */
    private String status;

    /** 创建时间，UTC。 */
    private Instant createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护。 */
    private Instant updatedAt;

    /** 软删除时间，NULL 表示未删除。 */
    private Instant deletedAt;
}
