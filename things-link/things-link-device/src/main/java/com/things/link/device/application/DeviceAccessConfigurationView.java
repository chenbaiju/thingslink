package com.things.link.device.application;

import com.things.link.shared.message.TransportProtocol;
import java.util.List;

/**
 * 同事务持锁形成的最小管理视图，不携带凭据内容或Broker内部身份。
 * @param protocol 当前协议 @param enabled 当前开关 @param configVersion 当前配置版本
 * @param credentialVersion 当前凭据版本 @param configured 是否已有持久绑定
 * @param allowedProtocols 类型允许的协议 @param canManage 当前角色、生命周期和类型是否允许配置
 */
public record DeviceAccessConfigurationView(TransportProtocol protocol, boolean enabled, long configVersion,
        long credentialVersion, boolean configured, List<TransportProtocol> allowedProtocols, boolean canManage) {
    /** 协议列表为展示快照，不能被调用者修改或用作后续写许可。 */
    public DeviceAccessConfigurationView { allowedProtocols = List.copyOf(allowedProtocols); }
}
