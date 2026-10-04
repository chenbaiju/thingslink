package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立认证健康确认协议的有界规范合同。 */
public final class OtaCommitReceiptCodec {
    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "authorizationId", "manifestSha256", "receiptId", "permitId", "bootId", "evidence");
    /** 验证词法和闭集并冻结规范字节，不裁决设备安全事实。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-commit-receipt/v1");
        if (!(value.get("evidence") instanceof Map<?, ?>)) throw OtaConfirmationJson.invalid();
        @SuppressWarnings("unchecked") Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) value.get("evidence"));
        var evidence = OtaConfirmationJson.evidence(e);
        var typed = new Receipt("tc-ota-commit-receipt/v1",
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("receiptId")),
                OtaTrustBundleCodec.uuid(value.get("permitId")),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                evidence);
        byte[] canonical = new OtaCanonicalJson().writeObject(value);
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param receiptId 协议receiptId字段
     * @param permitId 协议permitId字段
     * @param bootId 协议bootId字段
     * @param evidence 协议evidence字段
     */
    public record Receipt(String contractVersion,
            UUID jobId,
            int attemptNo,
            UUID authorizationId,
            String manifestSha256,
            UUID receiptId,
            UUID permitId,
            UUID bootId,
            OtaJobProgressCodec.Evidence evidence) { }
    /** 规范事实与完整摘要。
     * @param value 类型化协议
     * @param canonical 完整规范字节
     * @param sha256 规范SHA256
     */
    public record Decoded(Receipt value, byte[] canonical, String sha256) {
        /** 防御复制输入。 */ public Decoded { canonical = canonical.clone(); }
        /** 防御复制输出。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
