package com.things.link.device.application;

import java.util.UUID;

/** 排程时冻结的设备身份事实，不证明凭据、报告或未来派发资格。
 * @param tenantId 权威租户
 * @param projectId 权威项目
 * @param deviceTypeId 已发布DIRECT类型
 * @param deviceId 明确选定设备
 * @param thingModelVersionId 当时模型绑定，未绑定时为null
 * @param credentialVersion 当时凭据代际，不代表存在有效凭据
 */
public record OtaDeviceTargetSnapshot(UUID tenantId, UUID projectId, UUID deviceTypeId,
        UUID deviceId, UUID thingModelVersionId, long credentialVersion) { }
