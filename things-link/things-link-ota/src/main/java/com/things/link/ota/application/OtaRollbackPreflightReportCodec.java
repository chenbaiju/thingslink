package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读回退预检规范合同，不提供设备执行权。 */
public final class OtaRollbackPreflightReportCodec {
    /** 闭集顶层字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "jobId", "attemptNo", "recoveryRevision", "authorizationId", "manifestSha256", "reportId", "bootId", "rollbackBaselineSha256", "bootloaderVersion", "activeSlot", "committedSecurityVersion", "evidence");
    /** 独立16KiB合同解析，安全矛盾由资格服务决定。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-rollback-preflight-report/v1");
        Evidence evidence = OtaRollbackPreflightJson.evidence(value.get("evidence"));
        var typed = new Report("tc-ota-rollback-preflight-report/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.integer(value, "recoveryRevision", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("reportId")),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                OtaConfirmationJson.hex(value, "rollbackBaselineSha256"),
                OtaRollbackPreflightJson.version(value.get("bootloaderVersion")),
                OtaConfirmationJson.literal(value, "activeSlot", Set.of("A", "B")),
                OtaConfirmationJson.integer(value, "committedSecurityVersion", 0, 9007199254740991L),
                evidence);
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 编码亦通过闭集校验。 */
    public byte[] encode(Report input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 类型化字段映射，不序列化额外实现字段。 */
    private static Map<String, Object> toMap(Report input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("recoveryRevision", input.recoveryRevision());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("reportId", String.valueOf(input.reportId()));
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("rollbackBaselineSha256", input.rollbackBaselineSha256());
        value.put("bootloaderVersion", input.bootloaderVersion());
        value.put("activeSlot", input.activeSlot());
        value.put("committedSecurityVersion", input.committedSecurityVersion());
        value.put("evidence", OtaRollbackPreflightJson.encode(input.evidence()));
        return value;
    }
    /** 不可变只读协议。
     * @param contractVersion 协议contractVersion字段
     * @param queryId 协议queryId字段
     * @param queryNonce 协议queryNonce字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param recoveryRevision 协议recoveryRevision字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param reportId 协议reportId字段
     * @param bootId 协议bootId字段
     * @param rollbackBaselineSha256 协议rollbackBaselineSha256字段
     * @param bootloaderVersion 协议bootloaderVersion字段
     * @param activeSlot 协议activeSlot字段
     * @param committedSecurityVersion 协议committedSecurityVersion字段
     * @param evidence 协议evidence字段
     */
    public record Report(String contractVersion,
            UUID queryId,
            UUID queryNonce,
            UUID jobId,
            int attemptNo,
            long recoveryRevision,
            UUID authorizationId,
            String manifestSha256,
            UUID reportId,
            UUID bootId,
            String rollbackBaselineSha256,
            String bootloaderVersion,
            String activeSlot,
            long committedSecurityVersion,
            Evidence evidence) { }
    /** 完整双槽与操作日志，只作观察。
     * @param hardware 协议hardware字段
     * @param slots 协议slots字段
     * @param journal 协议journal字段
     * @param writeState 协议writeState字段
     */
    public record Evidence(Hardware hardware,
            List<Slot> slots,
            Journal journal,
            String writeState) {
        /** 冻结集合，调用者不能替换原规范事实。 */
        public Evidence { slots = java.util.List.copyOf(slots); }
    }
    /** 原物理硬件标识。
     * @param model 协议model字段
     * @param boardRevision 协议boardRevision字段
     */
    public record Hardware(String model,
            long boardRevision) { }
    /** 单槽精确身份与完整性观察。
     * @param slot 协议slot字段
     * @param artifactSha256 协议artifactSha256字段
     * @param securityVersion 协议securityVersion字段
     * @param thingModelVersionId 协议thingModelVersionId字段
     * @param thingModelSchemaDigestAlgorithm 协议thingModelSchemaDigestAlgorithm字段
     * @param thingModelSchemaDigest 协议thingModelSchemaDigest字段
     * @param propertyProfile 协议propertyProfile字段
     * @param integrity 协议integrity字段
     * @param bootable 协议bootable字段
     * @param bootloaderVerified 协议bootloaderVerified字段
     * @param health 协议health字段
     */
    public record Slot(String slot,
            String artifactSha256,
            long securityVersion,
            UUID thingModelVersionId,
            String thingModelSchemaDigestAlgorithm,
            String thingModelSchemaDigest,
            String propertyProfile,
            String integrity,
            boolean bootable,
            boolean bootloaderVerified,
            String health) { }
    /** 受控原子操作Profile的耐久日志声明。
     * @param atomicOperationProfile 协议atomicOperationProfile字段
     * @param operationRevision 协议operationRevision字段
     * @param state 协议state字段
     * @param commitOperations 协议commitOperations字段
     * @param rollbackOperationIds 协议rollbackOperationIds字段
     */
    public record Journal(String atomicOperationProfile,
            long operationRevision,
            String state,
            List<CommitOperation> commitOperations,
            List<UUID> rollbackOperationIds) {
        /** 冻结集合，调用者不能替换原规范事实。 */
        public Journal { commitOperations = java.util.List.copyOf(commitOperations); rollbackOperationIds = java.util.List.copyOf(rollbackOperationIds); }
    }
    /** 原提交许可明确处置。
     * @param permitId 协议permitId字段
     * @param state 协议state字段
     */
    public record CommitOperation(UUID permitId,
            String state) { }
    /** 规范字节及摘要。
     * @param value 协议value字段
     * @param canonical 协议canonical字段
     * @param sha256 协议sha256字段
     */
    public record Decoded(Report value,
            byte[] canonical,
            String sha256) {
        /** 输入防御复制。 */ public Decoded { canonical = canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
