package com.things.link.device.application;

import com.things.link.device.domain.ThingModelVersion;

import java.util.Map;
import java.util.UUID;

/**
 * device 域对上行声明版本的一次性裁决；下游不得重新解释或把 HISTORY_ONLY 提升为当前链资格。
 * @param tenantId 可信设备租户归属 @param thingModelVersionId 写入时版本 ID
 * @param versionNumber 可读版本 @param schemaDigest Schema 摘要
 * @param digestAlgorithm 规范化与摘要算法版本 @param modelSnapshot 不可变模型快照
 * @param eligibility 当前链或仅历史资格 @param propertyDataTypes 本批属性的冻结类型映射
 * @param legacyInferred 是否由存量标量省略 modelVersion 推断而来；推断链路禁止携带 OBJECT/LIST
 */
public record DeviceIngestionContext(UUID tenantId, UUID thingModelVersionId, String versionNumber,
                                     String schemaDigest, String digestAlgorithm, String modelSnapshot,
                                     Eligibility eligibility, Map<String, String> propertyDataTypes,
                                     boolean legacyInferred) {
    /** 冻结下游消费的类型映射，避免校验后仍被调用方修改。 */
    public DeviceIngestionContext {
        propertyDataTypes = propertyDataTypes == null ? Map.of() : Map.copyOf(propertyDataTypes);
    }

    /** 旧版本消息只能写历史/消息日志；CURRENT 才能推进影子、实时、规则与告警。 */
    public enum Eligibility { /** 当前行为链。 */ CURRENT, /** 仅历史。 */ HISTORY_ONLY }

    /** @param version 已解析版本 @param eligibility 资格 @return 稳定应用端口结果 */
    public static DeviceIngestionContext from(ThingModelVersion version, Eligibility eligibility) {
        return new DeviceIngestionContext(version.tenantId(), version.id(), version.versionNumber(),
                version.schemaDigest(), version.digestAlgorithm(), version.modelSnapshot(), eligibility, Map.of(),
                false);
    }

    /** @param types 已验证属性类型 @return 保留同一版本资格与推断标志的新不可变上下文 */
    public DeviceIngestionContext withPropertyDataTypes(Map<String, String> types) {
        return new DeviceIngestionContext(tenantId, thingModelVersionId, versionNumber, schemaDigest,
                digestAlgorithm, modelSnapshot, eligibility, types, legacyInferred);
    }

    /** @return 标记为存量标量省略 modelVersion 推断出的当前版本上下文 */
    public DeviceIngestionContext withLegacyInferred() {
        return new DeviceIngestionContext(tenantId, thingModelVersionId, versionNumber, schemaDigest,
                digestAlgorithm, modelSnapshot, eligibility, propertyDataTypes, true);
    }
}
