package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 原子回退独立闭集协议，结构合法不代表设备动作获得授权。 */
public final class OtaRollbackOperationCodec {
    /** 完整顶层白名单。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion",
            "operationId",
            "jobId",
            "attemptNo",
            "authorizationId",
            "manifestSha256",
            "queryId",
            "reportId",
            "reportSha256",
            "rollbackBaselineSha256",
            "expectedBootId",
            "expectedOperationRevision",
            "expectedCommittedSecurityVersion",
            "source",
            "targetSlot",
            "permitIds",
            "expiresAt");
    /** 限定原始十六KiB并恢复规范字节，不在词法层丢弃未知状态。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-rollback-operation/v1");
        var typed = new Operation(
                "tc-ota-rollback-operation/v1",
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("reportId")),
                OtaConfirmationJson.hex(value, "reportSha256"),
                OtaConfirmationJson.hex(value, "rollbackBaselineSha256"),
                OtaTrustBundleCodec.uuid(value.get("expectedBootId")),
                OtaConfirmationJson.integer(value, "expectedOperationRevision", 0, 9007199254740990L),
                OtaConfirmationJson.integer(value, "expectedCommittedSecurityVersion", 0, 9007199254740991L),
                OtaRollbackExecutionJson.source(value.get("source")),
                OtaConfirmationJson.literal(value, "targetSlot", Set.of("A", "B")),
                OtaRollbackPreflightJson.ids(value.get("permitIds")),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入同样执行完整词法与预算检查。 */
    public byte[] encode(Operation input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 显式白名单编码，避免序列化实现细节。 */
    private static Map<String, Object> toMap(Operation input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("reportId", String.valueOf(input.reportId()));
        value.put("reportSha256", input.reportSha256());
        value.put("rollbackBaselineSha256", input.rollbackBaselineSha256());
        value.put("expectedBootId", String.valueOf(input.expectedBootId()));
        value.put("expectedOperationRevision", input.expectedOperationRevision());
        value.put("expectedCommittedSecurityVersion", input.expectedCommittedSecurityVersion());
        value.put("source", OtaRollbackExecutionJson.encode(input.source()));
        value.put("targetSlot", input.targetSlot());
        value.put("permitIds", input.permitIds().stream().map(UUID::toString).toList());
        value.put("expiresAt", input.expiresAt());
        return value;
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param operationId 协议operationId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param queryId 协议queryId字段
     * @param reportId 协议reportId字段
     * @param reportSha256 协议reportSha256字段
     * @param rollbackBaselineSha256 协议rollbackBaselineSha256字段
     * @param expectedBootId 协议expectedBootId字段
     * @param expectedOperationRevision 协议expectedOperationRevision字段
     * @param expectedCommittedSecurityVersion 协议expectedCommittedSecurityVersion字段
     * @param source 协议source字段
     * @param targetSlot 协议targetSlot字段
     * @param permitIds 协议permitIds字段
     * @param expiresAt 协议expiresAt字段
     */
    public record Operation(String contractVersion,
            UUID operationId,
            UUID jobId,
            int attemptNo,
            UUID authorizationId,
            String manifestSha256,
            UUID queryId,
            UUID reportId,
            String reportSha256,
            String rollbackBaselineSha256,
            UUID expectedBootId,
            long expectedOperationRevision,
            long expectedCommittedSecurityVersion,
            Source source,
            String targetSlot,
            List<UUID> permitIds,
            long expiresAt) {
        /** 集合防御复制，拒绝空引用及空元素。 */
        public Operation {
            if (permitIds == null || permitIds.stream().anyMatch(java.util.Objects::isNull)) throw OtaConfirmationJson.invalid();
            permitIds = List.copyOf(permitIds);
        }
    }
    /** 不可变协议字段。
     * @param slot 协议slot字段
     * @param artifactSha256 协议artifactSha256字段
     * @param securityVersion 协议securityVersion字段
     * @param thingModelVersionId 协议thingModelVersionId字段
     * @param thingModelSchemaDigestAlgorithm 协议thingModelSchemaDigestAlgorithm字段
     * @param thingModelSchemaDigest 协议thingModelSchemaDigest字段
     * @param propertyProfile 协议propertyProfile字段
     */
    public record Source(String slot,
            String artifactSha256,
            long securityVersion,
            UUID thingModelVersionId,
            String thingModelSchemaDigestAlgorithm,
            String thingModelSchemaDigest,
            String propertyProfile) { }
    /** 解码结果，规范字节不向调用方共享。
     * @param value 不可变协议
     * @param canonical 规范字节
     * @param sha256 完整摘要
     */
    public record Decoded(Operation value, byte[] canonical, String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 输出字节防御复制。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
}
