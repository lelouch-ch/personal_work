package com.example.pwt.identity.domain;

/**
 * 用户状态，与 {@code pt_user.status} 的 CHECK 取值一致。
 *
 * @author pwt
 */
public enum UserStatus {

    /** 正常可用。 */
    ACTIVE("active"),
    /** 已停用，不允许登录与签发。 */
    DISABLED("disabled"),
    /** 已锁定，需管理员解锁。 */
    LOCKED("locked");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    /**
     * 获取数据库存储值。
     *
     * @return 存储值
     */
    public String getValue() {
        return value;
    }

    /**
     * 按数据库存储值解析状态。
     *
     * @param value 存储值
     * @return 状态枚举
     */
    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
