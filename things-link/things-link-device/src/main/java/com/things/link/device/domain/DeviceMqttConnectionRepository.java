package com.things.link.device.domain;

import java.util.Optional;
import java.util.UUID;

/** ADR0200原始连接账本；所有操作须处于调用方业务事务，状态/来源与返回决策同提交。 */
public interface DeviceMqttConnectionRepository {
    /** 冻结的原身份及有效Client ID，不接受设备自报版本替代原认证。 */
    record Scope(UUID tenantId, UUID projectId, UUID deviceId, long credentialVersion,
                 long configVersion, String sessionId) {
        /** 拒绝半套范围或非法持久会话名。 */
        public Scope {
            if (tenantId == null || projectId == null || deviceId == null || credentialVersion < 0
                    || configVersion < 0 || sessionId == null || !sessionId.matches("tc-device-[0-9a-f]{64}"))
                throw new IllegalArgumentException("MQTT连接范围无效");
        }
    }
    /** 成功签发后返回随机连接身份；次序只在服务端参与比较。 */
    record Ticket(UUID id, long order) { }
    /** 区分已接受重放与首次写入，避免重复来源/网关推送/指标。 */
    record Transition(boolean accepted, boolean changed, int closedConnections) {
        /** 固定拒绝不产生副作用。 */
        public static Transition rejected() { return new Transition(false, false, 0); }
    }
    /** 当前项目/凭据/协议资格、容量与签发同事务；无许可返回空。 */
    Optional<Ticket> issue(Scope scope);
    /** 仅接受本票据当前未过期的新连接，调用方在同事务追加在线来源。 */
    Transition connected(Scope scope, UUID ticketId, String clientIp, String node);
    /** 只关闭原连接，先断后连持久拒绝；调用方同事务更新在线/拓扑来源。 */
    Transition disconnected(Scope scope, UUID ticketId, String reason);
    /** ACL只允许当前有效ACTIVE或有限PENDING，数据库故障不降级。 */
    boolean permits(Scope scope, UUID ticketId);
}
