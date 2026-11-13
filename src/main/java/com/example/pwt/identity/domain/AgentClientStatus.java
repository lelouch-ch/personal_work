package com.example.pwt.identity.domain;

/**
 * Agent 客户端状态，与 {@code pt_agent_client.status} 的 CHECK 取值一致。
 *
 * @author pwt
 */
public enum AgentClientStatus {

    /** 可用。 */
    ACTIVE("active"),
    /** 已撤销，请求立即失效（验收用例 11）。 */
    REVOKED("revoked"),
    /** 已过期。 */
    EXPIRED("expired");

    private final String value;

    AgentClientStatus(String value) {
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
    public static AgentClientStatus fromValue(String value) {
        for (AgentClientStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown agent client status: " + value);
    }
}
