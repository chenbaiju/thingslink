package com.things.link.device.application;

import java.util.UUID;

/** 不携带产品密钥、模型正文或制造能力的权威类型身份。
 * @param tenantId 类型真实所属租户
 * @param projectId 类型所属项目
 * @param deviceTypeId 已发布设备类型身份
 * @param productKey 当前权威产品标识
 */
public record OtaDeviceTypeIdentity(UUID tenantId, UUID projectId, UUID deviceTypeId, String productKey) { }
