package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;

import java.util.Optional;

/**
 * 协议无关的设备一机一密校验端口。
 *
 * <p>MQTT（EMQX HTTP 认证回调）与新增 HTTP／TCP／CoAP 接入必须共用同一份凭据事实与同一份成功缓存：
 * 各协议各写一份校验不仅会漂移哈希与 RLS 口径，更会让「撤销在一个心跳周期内失效」（ADR 0140）只对
 * MQTT 成立。实现因此同时是唯一的 {@code DEVICE_CREDENTIAL} 缓存失效处理器，撤销事件到达后立即驱逐
 * 对应设备的成功条目。</p>
 *
 * <p>本端口<b>只</b>回答「凭据是否对应该设备」，不承担任何 MQTT 专属前提：内部 durable 订阅服务身份与
 * Broker 首会话就绪门禁留在 EMQX 回调门面内，新协议不得被无关的 Broker 状态拒绝。</p>
 *
 * <p>失败一律返回空，且实现不得记录明文密钥：未知项目、未知设备、密钥不匹配与凭据被撤销／到期在事实层
 * 可能无法区分，把它们拆成不同错误会给出设备枚举 oracle。具体错误码到协议响应的映射由拥有该协议的切片
 * 负责，本端口不预先发明无法自证的区别。</p>
 */
public interface DeviceAuthenticationPort {

    /**
     * 校验一台设备的接入凭据。
     *
     * @param projectKey 项目短标识，必须符合 {@link DeviceAccessIdentifiers} 字符集
     * @param deviceKey 设备短标识，必须符合 {@link DeviceAccessIdentifiers} 字符集
     * @param secret 设备密钥明文，只用于本地哈希比较，禁止写入日志或审计
     * @return 校验通过时的认证身份快照；任何失败均为空
     */
    Optional<AuthenticatedDeviceIdentity> authenticate(String projectKey, String deviceKey, String secret);
}
