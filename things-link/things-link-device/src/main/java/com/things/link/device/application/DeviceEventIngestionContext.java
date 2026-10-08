package com.things.link.device.application;

import java.util.UUID;

/**
 * 事件首次摄入的可信不可变模型裁决，下游只能保存原资格，不能提升为属性或规则副作用。
 * @param tenantId 设备实际租户
 * @param deviceTypeId 设备实际类型
 * @param thingModelVersionId 声明所对应的不可变模型版本
 * @param modelVersion 冻结语义版本
 * @param schemaDigest 原模型摘要
 * @param digestAlgorithm 原摘要算法
 * @param eventKey 原事件标识
 * @param level 原模型事件级别
 * @param eligibility 当前版本或直接替换窗口内的仅历史资格
 */
public record DeviceEventIngestionContext(UUID tenantId, UUID deviceTypeId, UUID thingModelVersionId,
                                          String modelVersion, String schemaDigest, String digestAlgorithm,
                                          String eventKey, String level, DeviceIngestionContext.Eligibility eligibility) {
}
