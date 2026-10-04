package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.Optional;

/** 调用方先锁定ACTIVE项目并完成主体授权，端口不构造管理账号或替代设备报告资格。 */
public interface OtaDeviceIdentityPort {
    /** 必须有外层事务；按类型再设备共享锁保留当前身份，旧代际/不可见/绑定不完整或当前不具备MQTT资格均为空。 */
    Optional<OtaDeviceIdentity> lockCurrent(AuthenticatedDeviceIdentity authenticated);
    /** 仅在lockCurrent已持锁的同一事务中，以数据库真实时钟最终复核凭据；不是独立完整授权。 */
    boolean credentialValid(AuthenticatedDeviceIdentity authenticated);
}
