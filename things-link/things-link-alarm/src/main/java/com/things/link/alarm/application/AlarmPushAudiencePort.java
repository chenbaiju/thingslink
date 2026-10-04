package com.things.link.alarm.application;

import java.util.List;
import java.util.UUID;

/**
 * 告警域查询当前有效 PUSH 安装受众的跨模块应用端口（ADR 0038）。
 *
 * <p>端口由 alarm 定义、enduser 适配实现，防止 alarm 直接读取 {@code app_*} 表。A2b 只返回
 * 展开投递事实所需的稳定 ID；厂商、密文和明文 token 均留在 enduser 的 A2c 发送边界。</p>
 */
public interface AlarmPushAudiencePort {

    /**
     * 查询仍同时满足用户、项目角色、设备关系和安装实例有效的 PUSH 受众。
     *
     * @param tenantId 告警事实携带的可信租户 ID
     * @param projectId 告警事实携带的可信项目 ID
     * @param deviceId 告警来源设备 ID
     * @return 按用户和安装实例稳定排序的有效受众
     */
    List<PushAudience> listActiveInstallations(UUID tenantId, UUID projectId, UUID deviceId);

    /**
     * 每个安装实例一条的最小受众身份。
     *
     * @param appUserId 终端用户稳定 ID
     * @param pushTokenId {@code app_push_token} 稳定事实 ID
     */
    record PushAudience(UUID appUserId, UUID pushTokenId) {}
}
