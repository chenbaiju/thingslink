package com.things.link.device.infrastructure.emqx;

import com.things.link.device.application.DeviceAccessIdentifiers;
import com.things.link.device.application.DeviceMqttAccessService;
import com.things.link.device.application.DeviceMqttSessionIdentity;
import com.things.link.device.application.DeviceAuthenticationPort;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * EMQX HTTP 认证回调门面。
 *
 * <p>凭据校验本身由协议无关的 {@link DeviceAuthenticationPort} 承担，本类负责 MQTT 专属前提：当前平面许可、
 * ADR 0046 的唯一 durable 订阅服务身份，以及首个 durable 会话建立前的设备拒绝（封闭零 HTTP Action
 * 首启竞态）。这些限制不下沉到协议无关凭据端口，避免 HTTP／TCP／CoAP 因无关的 MQTT 状态被拒绝。</p>
 */
@Service
public class EmqxAuthService {

    /** ADR0192：成功凭据缓存不能缓存当前MQTT平面许可。 */
    private final DeviceMqttAccessService access;
    /** 与三协议接入共用的凭据校验端口。 */
    private final DeviceAuthenticationPort credentials;
    /** ADR 0046 的唯一内部订阅身份；密码只从环境绑定，不进入设备凭据表。 */
    private final BrokerIngressProperties ingressProperties;
    /** 首个 durable session 建立前拒绝设备，封闭零 HTTP Action 首启竞态。 */
    private final BrokerIngressReadiness ingressReadiness;

    /**
     * 创建 EMQX 认证门面。
     *
     * @param credentials 协议无关设备凭据校验端口
     * @param ingressProperties 内部订阅身份属性
     * @param ingressReadiness durable 订阅就绪状态
     * @param access 当前MQTT配置许可，不替换原凭据版本
     */
    public EmqxAuthService(DeviceAuthenticationPort credentials, BrokerIngressProperties ingressProperties,
                           BrokerIngressReadiness ingressReadiness, DeviceMqttAccessService access) {
        this.access = access;
        this.credentials = credentials;
        this.ingressProperties = ingressProperties;
        this.ingressReadiness = ingressReadiness;
    }

    /**
     * 校验一机一密凭据。
     *
     * @param username {@code projectKey/deviceKey}
     * @param password 设备密钥明文
     * @param clientId 原MQTT Client ID，用于服务器会话隔离
     * @return 允许或拒绝
     */
    public EmqxAuthResult authenticate(String username, String password, String clientId) {
        return authenticateIdentity(username, password, clientId).result();
    }

    /**
     * 返回本次凭据校验的身份快照，历史缓存不得补查为新代际。
     *
     * @param username MQTT 用户名或内部订阅身份
     * @param password 设备密钥明文或内部订阅密码
     * @param clientId MQTT Client ID
     * @return 认证结果与同一次校验的设备身份
     */
    public Authentication authenticateIdentity(String username, String password, String clientId) {
        // 服务身份没有 project/device 两段式用户名，必须在设备解析前精确匹配固定 clientId 与环境密码。
        if (ingressProperties.username().equals(username)) {
            return new Authentication(ingressProperties.matches(username, password, clientId)
                    ? EmqxAuthResult.ALLOW : EmqxAuthResult.DENY, null, null, null);
        }
        // 服务身份必须先连入才能创建会话；其余设备只在 SUBACK 后放行，避免首次启动消息落入无订阅窗口。
        if (ingressProperties.enabled() && !ingressReadiness.isReady()) return denied();
        if (password == null || !validUsername(username) || !DeviceMqttSessionIdentity.validClientId(clientId)) return denied();
        String[] parts = username.split("/", -1);
        var identity = credentials.authenticate(parts[0], parts[1], password);
        if (identity.isEmpty()) return denied();
        var grant = access.issueConnection(parts[0], parts[1], identity.get(), clientId);
        return grant.isPresent()
                ? new Authentication(EmqxAuthResult.ALLOW, identity.get(), grant.get().configVersion(), grant.get().connectionId()) : denied();
    }

    /** 用户名必须恰好包含两个符合共享标识符字符集的段。 */
    private static boolean validUsername(String username) {
        if (username == null) {
            return false;
        }
        String[] parts = username.split("/", -1);
        return parts.length == 2 && DeviceAccessIdentifiers.isValid(parts[0])
                && DeviceAccessIdentifiers.isValid(parts[1]);
    }

    /** 固定拒绝结果不携带设备身份。 */
    private static Authentication denied() { return new Authentication(EmqxAuthResult.DENY, null, null, null); }

    /** 本次认证快照；服务身份允许成功但没有设备属性。
     * @param result Broker认证决策
     * @param identity 同一次凭据校验的设备身份，拒绝或服务身份为空
     * @param connectionId 原认证随机连接票据；非设备结果为空
     * @param configVersion 同一许可观察点的配置代次；非设备结果为空
     */
    public record Authentication(EmqxAuthResult result, AuthenticatedDeviceIdentity identity, Long configVersion, UUID connectionId) {
        /** 防止拒绝响应意外携带设备身份。 */
        public Authentication {
            if (result == null || result != EmqxAuthResult.ALLOW && identity != null
                    || (identity == null) != (connectionId == null) || (identity == null) != (configVersion == null) || configVersion != null && configVersion < 0)
                throw new IllegalArgumentException("认证结果不合法");
        }
    }

    /** EMQX 5 HTTP 认证响应支持的确定性结果。 */
    public enum EmqxAuthResult { ALLOW, DENY }
}
