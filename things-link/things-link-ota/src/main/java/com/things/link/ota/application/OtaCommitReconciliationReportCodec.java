package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立只读提交对账的有界规范合同，不扩展旧协议。 */
public final class OtaCommitReconciliationReportCodec {
    /** 精确闭集字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "jobId", "attemptNo", "recoveryRevision", "authorizationId", "manifestSha256", "permitId", "reportId", "bootId", "commitBootId", "evidence");
    /** 验证词法及规范JSON；目标安全语义由事务服务判断。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-commit-reconciliation-report/v1");
        if (!(value.get("evidence") instanceof Map<?, ?>)) throw OtaConfirmationJson.invalid();
        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) value.get("evidence");
        var evidence = OtaConfirmationJson.evidence(e);
        var typed = new Report("tc-ota-commit-reconciliation-report/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.integer(value, "recoveryRevision", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("permitId")),
                OtaTrustBundleCodec.uuid(value.get("reportId")),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                OtaTrustBundleCodec.uuid(value.get("commitBootId")),
                evidence);
        byte[] canonical = new OtaCanonicalJson().writeObject(value);
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 输出仍走完整解析校验，不能借类型化对象绕过范围。 */
    public byte[] encode(Report input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("recoveryRevision", input.recoveryRevision());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("permitId", String.valueOf(input.permitId()));
        value.put("reportId", String.valueOf(input.reportId()));
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("commitBootId", String.valueOf(input.commitBootId()));
        if (input.evidence() == null) throw OtaConfirmationJson.invalid();
        var proof = input.evidence();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("artifactSha256", proof.artifactSha256());
        e.put("artifactSize", proof.artifactSize());
        e.put("securityVersion", proof.securityVersion());
        e.put("committedSecurityVersion", proof.committedSecurityVersion());
        e.put("thingModelVersionId", String.valueOf(proof.thingModelVersionId()));
        e.put("thingModelSchemaDigestAlgorithm", proof.thingModelSchemaDigestAlgorithm());
        e.put("thingModelSchemaDigest", proof.thingModelSchemaDigest());
        e.put("propertyProfile", proof.propertyProfile());
        e.put("trustDomain", proof.trustDomain());
        e.put("rootFingerprint", proof.rootFingerprint());
        e.put("trustBundleVersion", proof.trustBundleVersion());
        e.put("trustBundleSha256", proof.trustBundleSha256());
        e.put("sourceSlot", proof.sourceSlot());
        e.put("targetSlot", proof.targetSlot());
        e.put("activeSlot", proof.activeSlot());
        e.put("verification", proof.verification());
        e.put("bootVerified", proof.bootVerified());
        e.put("selfTestPassed", proof.selfTestPassed());
        e.put("watchdogHealthy", proof.watchdogHealthy());
        value.put("evidence", e);
        return decode(new OtaCanonicalJson().writeObject(value)).canonical();
    }
    /** 不可变协议投影。
     * @param contractVersion 协议contractVersion字段
     * @param queryId 协议queryId字段
     * @param queryNonce 协议queryNonce字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param recoveryRevision 协议recoveryRevision字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param permitId 协议permitId字段
     * @param reportId 协议reportId字段
     * @param bootId 协议bootId字段
     * @param commitBootId 协议commitBootId字段
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
            UUID permitId,
            UUID reportId,
            UUID bootId,
            UUID commitBootId,
            OtaJobProgressCodec.Evidence evidence) { }
    /** 规范事实。
     * @param value 类型化合同
     * @param canonical 完整规范字节
     * @param sha256 规范摘要
     */
    public record Decoded(Report value, byte[] canonical, String sha256) {
        /** 冻结输入。 */ public Decoded { canonical = canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
