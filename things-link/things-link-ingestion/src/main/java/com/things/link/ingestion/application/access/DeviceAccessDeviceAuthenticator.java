package com.things.link.ingestion.application.access;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;

/**
 * 新协议设备认证应用端口：调用方（协议过滤器／会话处理器）只交给它已解析的凭据，不自己拼装身份。
 *
 * <p>它把三件事收在一处：凭据校验（复用 device 域的 {@code DeviceAuthenticationPort}）、接入平面资格
 * （设备是否已开通该协议、配置是否启用）、活动事实（无会话协议记录最近活动）。因此「认证通过」始终意味着
 * 「这台设备此刻确实可以使用这个平面」，而不是仅仅「密钥对得上」。</p>
 */
public interface DeviceAccessDeviceAuthenticator {

    /**
     * 校验设备凭据并确认其有权使用指定接入平面。
     *
     * @param protocol 设备接入平面的传输协议
     * @param projectKey 项目短标识
     * @param deviceKey 设备短标识
     * @param secret 设备密钥明文
     * @return 认证身份快照
     * @throws DeviceAccessDeviceAuthenticationException 凭据无效、设备未开通该平面或配置被关闭
     */
    AuthenticatedDeviceIdentity authenticate(com.things.link.shared.message.TransportProtocol protocol,
                                             String projectKey, String deviceKey, String secret);
}
