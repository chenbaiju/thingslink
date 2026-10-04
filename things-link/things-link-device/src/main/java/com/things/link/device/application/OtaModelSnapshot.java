package com.things.link.device.application;

import java.util.UUID;

/**
 * 固件草稿的精确类型与已发布模型投影，不证明硬件能力或OTA签名资格。
 * @param projectId 权威项目归属
 * @param deviceTypeId 精确设备类型
 * @param thingModelVersionId 不可变模型版本
 * @param productKey 已发布类型的稳定产品标识
 * @param schemaDigestAlgorithm 权威快照摘要算法
 * @param schemaDigest 权威模型摘要
 * @param schemaProfile 权威模型能力合同
 */
public record OtaModelSnapshot(UUID projectId, UUID deviceTypeId, UUID thingModelVersionId,
                               String productKey, String schemaDigestAlgorithm,
                               String schemaDigest, String schemaProfile) { }
