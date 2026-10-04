package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不可变物模型发布版本；快照和摘要共同作为历史、看板与 OTA 的唯一解释依据。
 *
 * @param id 版本 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param deviceTypeId 设备类型 ID
 * @param versionNumber 规范 major.minor.patch
 * @param changeLevel 相对前版最低变化级别
 * @param modelSnapshot 规范 JSON 快照
 * @param schemaDigest 规范快照 SHA-256 @param digestAlgorithm 规范化与摘要算法版本
 * @param publishedAt 发布时刻
 */
public record ThingModelVersion(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                String versionNumber, ChangeLevel changeLevel, String modelSnapshot,
                                String schemaDigest, String digestAlgorithm, Instant publishedAt) {
    /** ADR 0039 / X-01 8：不兼容变化不能伪装成 PATCH/MINOR。 */
    public enum ChangeLevel { /** 展示元数据变化。 */ PATCH, /** 兼容新增。 */ MINOR, /** 不兼容变化。 */ MAJOR }
}
