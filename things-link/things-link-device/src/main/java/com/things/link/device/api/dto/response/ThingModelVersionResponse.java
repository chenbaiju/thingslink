package com.things.link.device.api.dto.response;

import com.things.link.device.domain.ThingModelVersion;

import java.time.Instant;
import java.util.UUID;

/**
 * 物模型已发布版本响应。
 *
 * <p>只投影公开的解释依据：版本 ID、规范版本号、变化级别、快照摘要与算法、发布时间。
 * 不返回完整模型快照——它属于草稿与设计器读取面，OTA 固件只须绑定一个不可变版本身份
 * 与摘要，多下发快照只会让下载端多一份需要保持一致的副本。
 *
 * @param id 版本 ID
 * @param deviceTypeId 设备类型 ID
 * @param versionNumber 规范 major.minor.patch
 * @param changeLevel 相对前版最低变化级别
 * @param schemaDigest 规范快照 SHA-256
 * @param digestAlgorithm 规范化与摘要算法版本
 * @param publishedAt 发布时刻
 */
public record ThingModelVersionResponse(UUID id, UUID deviceTypeId, String versionNumber,
                                        ThingModelVersion.ChangeLevel changeLevel, String schemaDigest,
                                        String digestAlgorithm, Instant publishedAt) {
    /**
     * @param version 领域版本
     * @return API 响应
     */
    public static ThingModelVersionResponse from(ThingModelVersion version) {
        return new ThingModelVersionResponse(version.id(), version.deviceTypeId(), version.versionNumber(),
                version.changeLevel(), version.schemaDigest(), version.digestAlgorithm(), version.publishedAt());
    }
}
