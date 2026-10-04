package com.things.link.ota.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** 完整字段文档；实际输入仍由原文codec严格验证。
 * @param contractVersion manifest字段contractVersion
 * @param firmwareId manifest字段firmwareId
 * @param firmwareVersion manifest字段firmwareVersion
 * @param trustDomain manifest字段trustDomain
 * @param deviceTypeId manifest字段deviceTypeId
 * @param productKey manifest字段productKey
 * @param hardware manifest字段hardware
 * @param bootloaderMinimumVersion manifest字段bootloaderMinimumVersion
 * @param artifactSize manifest字段artifactSize
 * @param artifactSha256 manifest字段artifactSha256
 * @param compression manifest字段compression
 * @param delta manifest字段delta
 * @param securityVersion manifest字段securityVersion
 * @param thingModelVersionId manifest字段thingModelVersionId
 * @param thingModelSchemaDigestAlgorithm manifest字段thingModelSchemaDigestAlgorithm
 * @param thingModelSchemaDigest manifest字段thingModelSchemaDigest
 * @param allowedSourceThingModelVersionIds manifest字段allowedSourceThingModelVersionIds
 * @param requirements manifest字段requirements
 * @param signatureProfile manifest字段signatureProfile
 * @param signingKeyFingerprint manifest字段signingKeyFingerprint
 * @param minimumTrustBundleVersion manifest字段minimumTrustBundleVersion
 */
@Schema(name = "OtaPublicationManifest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"contractVersion", "firmwareId", "firmwareVersion", "trustDomain", "deviceTypeId", "productKey", "hardware", "bootloaderMinimumVersion", "artifactSize", "artifactSha256", "compression", "delta", "securityVersion", "thingModelVersionId", "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest", "allowedSourceThingModelVersionIds", "requirements", "signatureProfile", "signingKeyFingerprint", "minimumTrustBundleVersion"})
public record OtaPublicationManifestBody(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"tc-ota-manifest/v1"}) String contractVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID firmwareId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String firmwareVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String trustDomain,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceTypeId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String productKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Hardware hardware,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})") String bootloaderMinimumVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991") long artifactSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String artifactSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"NONE"}) String compression,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Delta delta,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long securityVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID thingModelVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PG_JSONB_TEXT_V1_SHA256"}) String thingModelSchemaDigestAlgorithm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String thingModelSchemaDigest,
        @ArraySchema(minItems = 1, maxItems = 256, uniqueItems = true, schema = @Schema(type = "string", format = "uuid")) List<UUID> allowedSourceThingModelVersionIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Requirements requirements,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"}) String signatureProfile,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[0-9a-f]{64}") String signingKeyFingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991") long minimumTrustBundleVersion) {
/** 完整字段文档；实际输入仍由原文codec严格验证。
 * @param model manifest字段model
 * @param boardRevisionMin manifest字段boardRevisionMin
 * @param boardRevisionMax manifest字段boardRevisionMax
 */
@Schema(name = "OtaPublicationHardware", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"model", "boardRevisionMin", "boardRevisionMax"})
public record Hardware(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long boardRevisionMin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long boardRevisionMax) { }
/** 完整字段文档；实际输入仍由原文codec严格验证。
 * @param mode manifest字段mode
 */
@Schema(name = "OtaPublicationDelta", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"mode"})
public record Delta(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"NONE"}) String mode) { }
/** 完整字段文档；实际输入仍由原文codec严格验证。
 * @param profile manifest字段profile
 * @param minimumRamBytes manifest字段minimumRamBytes
 * @param minimumFlashBytes manifest字段minimumFlashBytes
 * @param requiresAbSlots manifest字段requiresAbSlots
 * @param requiresRangeDownload manifest字段requiresRangeDownload
 * @param requiresProtectedSecurityCounter manifest字段requiresProtectedSecurityCounter
 */
@Schema(name = "OtaPublicationRequirements", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"profile", "minimumRamBytes", "minimumFlashBytes", "requiresAbSlots", "requiresRangeDownload", "requiresProtectedSecurityCounter"})
public record Requirements(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"TC_PROPERTY_COMPOSITE_V1"}) String profile,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long minimumRamBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991") long minimumFlashBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requiresAbSlots,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requiresRangeDownload,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"true"}) boolean requiresProtectedSecurityCounter) { }
}
