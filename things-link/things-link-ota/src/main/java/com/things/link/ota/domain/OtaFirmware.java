package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 固件持久事实，API必须显式投影以排除租户及创建账号。
 * @param id 固件身份
 * @param tenantId 项目所有者租户
 * @param projectId 所属项目
 * @param createdBy 创建账号
 * @param deviceTypeId 精确设备类型
 * @param thingModelVersionId 精确模型版本
 * @param productKey 产品标识快照
 * @param firmwareVersion 原样展示版本
 * @param schemaDigestAlgorithm 摘要算法
 * @param schemaDigest 模型摘要
 * @param schemaProfile 模型合同
 * @param status 草稿、发布过程、READY或软终态
 * @param revision 乐观版本
 * @param createdAt 创建时刻
 * @param cancelledAt 取消时刻
 */
public record OtaFirmware(UUID id, UUID tenantId, UUID projectId, UUID createdBy,
                          UUID deviceTypeId, UUID thingModelVersionId, String productKey,
                          String firmwareVersion, String schemaDigestAlgorithm, String schemaDigest,
                          String schemaProfile, String status, long revision, Instant createdAt,
                          Instant cancelledAt) { }
