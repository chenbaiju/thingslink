package com.things.link.support.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Broker durable handoff 的单活服务身份与连接参数。
 *
 * <p>ADR 0046 要求认证端与 MQTT 客户端读取同一组环境变量，避免在 EMQX HOCON 中保存密码。
 * {@code enabled=false} 只用于测试和迁移窗口；删除旧 HTTP message action 的运行环境必须显式启用。</p>
 *
 * @param enabled 是否启动固定单活 ingress
 * @param brokerUri MQTT 3.1.1 Broker URI
 * @param username 只用于内部订阅的服务用户名
 * @param password 不得写入仓库、日志或证据的服务密码
 * @param clientId 独立部署的单活 MQTT 客户端身份；认证端与订阅端必须一致
 */
@ConfigurationProperties(prefix = "things-link.ingress.handoff")
public record BrokerIngressProperties(boolean enabled, String brokerUri, String username, String password,
                                      String clientId) {

    /** ADR 0046 原应用的默认 MQTT clientId；独立部署可显式提供自己的单活身份。 */
    public static final String CLIENT_ID = "thingslink-uplink-ingress-v1";
    /** 设备 ACL 永远不能访问的 Broker 内部交接 Topic。 */
    public static final String INTERNAL_TOPIC = "tc/internal/v1/ingress/uplink";
    /** 服务密码最低长度与 Broker 回调密钥一致，防止长期凭据退化为易猜口令。 */
    private static final int MINIMUM_PASSWORD_BYTES = 32;

    /** 对可公开字段应用安全默认；密码没有默认值，避免开发字面量进入版本库。 */
    @ConstructorBinding
    public BrokerIngressProperties {
        brokerUri = brokerUri == null || brokerUri.isBlank() ? "tcp://localhost:1883" : brokerUri.strip();
        username = username == null || username.isBlank() ? "thingslink-uplink-ingress" : username.strip();
        password = password == null ? "" : password;
        clientId = clientId == null || clientId.isBlank() ? CLIENT_ID : clientId.strip();
    }

    /** 保留原平台及测试的四参数构造，默认身份不变。 */
    public BrokerIngressProperties(boolean enabled, String brokerUri, String username, String password) {
        this(enabled, brokerUri, username, password, CLIENT_ID);
    }

    /**
     * 启用时一次性校验不可降级的不变量。
     *
     * @throws IllegalStateException URI、用户名或密码不满足冻结合同时拒绝启动
     */
    public void requireValidWhenEnabled() {
        if (!enabled) return;
        if (!(brokerUri.startsWith("tcp://") || brokerUri.startsWith("ssl://"))) {
            throw new IllegalStateException("durable ingress broker URI 只允许 tcp:// 或 ssl://");
        }
        if (username.contains("/") || username.length() > 128) {
            throw new IllegalStateException("durable ingress username 必须是不含斜杠的服务身份");
        }
        if (!clientId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("durable ingress clientId 必须是有效的单活服务身份");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length < MINIMUM_PASSWORD_BYTES) {
            throw new IllegalStateException("things-link.ingress.handoff.password 至少需要 32 字节");
        }
    }

    /**
     * 常量时间校验 Broker HTTP authenticator 转发的服务凭据。
     *
     * @param candidateUsername MQTT CONNECT 用户名
     * @param candidatePassword MQTT CONNECT 密码
     * @param candidateClientId MQTT CONNECT clientId
     * @return 是否为当前启用的唯一 ingress owner
     */
    public boolean matches(String candidateUsername, String candidatePassword, String candidateClientId) {
        if (!enabled || candidateUsername == null || candidatePassword == null || candidateClientId == null) {
            return false;
        }
        return username.equals(candidateUsername)
                && clientId.equals(candidateClientId)
                && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                candidatePassword.getBytes(StandardCharsets.UTF_8));
    }
}
