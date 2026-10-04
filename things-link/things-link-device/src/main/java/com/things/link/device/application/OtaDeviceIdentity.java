package com.things.link.device.application;

import java.util.UUID;
import java.time.Instant;

/** 当前DIRECT设备身份和不可变模型投影，不表示报告或目标固件已兼容。
 * @param tenantId 设备真实租户
 * @param projectId 设备项目
 * @param deviceId 当前设备
 * @param deviceTypeId 已发布DIRECT类型
 * @param productKey 类型产品标识
 * @param credentialVersion 当前凭据代际
 * @param thingModelVersionId 实际绑定不可变模型
 * @param schemaDigestAlgorithm 权威模型摘要合同
 * @param schemaDigest 权威模型摘要
 * @param schemaProfile 完整模型Profile
 * @param credentialExpiresAt 当前有效ACCESS_TOKEN原截止，null表示无截止
 */
public record OtaDeviceIdentity(UUID tenantId, UUID projectId, UUID deviceId, UUID deviceTypeId,
        String productKey, long credentialVersion, UUID thingModelVersionId, String schemaDigestAlgorithm,
        String schemaDigest, String schemaProfile, Instant credentialExpiresAt) {
}
