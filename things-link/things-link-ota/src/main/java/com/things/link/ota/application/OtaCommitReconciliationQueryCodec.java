package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立只读提交对账的有界规范合同，不扩展旧协议。 */
public final class OtaCommitReconciliationQueryCodec {
    /** 精确闭集字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "jobId", "attemptNo", "recoveryRevision", "authorizationId", "manifestSha256", "permitId", "commitBootId", "expiresAt");
    /** 验证词法及规范JSON；目标安全语义由事务服务判断。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-commit-reconciliation-query/v1");
        var typed = new Query("tc-ota-commit-reconciliation-query/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.integer(value, "recoveryRevision", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("permitId")),
                OtaTrustBundleCodec.uuid(value.get("commitBootId")),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(value);
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 输出仍走完整解析校验，不能借类型化对象绕过范围。 */
    public byte[] encode(Query input) {
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
        value.put("commitBootId", String.valueOf(input.commitBootId()));
        value.put("expiresAt", input.expiresAt());
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
     * @param commitBootId 协议commitBootId字段
     * @param expiresAt 协议expiresAt字段
     */
    public record Query(String contractVersion,
            UUID queryId,
            UUID queryNonce,
            UUID jobId,
            int attemptNo,
            long recoveryRevision,
            UUID authorizationId,
            String manifestSha256,
            UUID permitId,
            UUID commitBootId,
            long expiresAt) { }
    /** 规范事实。
     * @param value 类型化合同
     * @param canonical 完整规范字节
     * @param sha256 规范摘要
     */
    public record Decoded(Query value, byte[] canonical, String sha256) {
        /** 冻结输入。 */ public Decoded { canonical = canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
