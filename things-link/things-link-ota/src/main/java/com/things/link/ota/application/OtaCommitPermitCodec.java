package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立认证健康确认协议的有界规范合同。 */
public final class OtaCommitPermitCodec {
    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "permitId", "jobId", "attemptNo", "authorizationId", "manifestSha256", "bootId", "securityVersion", "artifactSha256", "targetSlot", "expiresAt");
    /** 验证词法和闭集并冻结规范字节，不裁决设备安全事实。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-commit-permit/v1");
        var typed = new Permit("tc-ota-commit-permit/v1",
                OtaTrustBundleCodec.uuid(value.get("permitId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                OtaConfirmationJson.integer(value, "securityVersion", 0, 9007199254740991L),
                OtaConfirmationJson.hex(value, "artifactSha256"),
                OtaConfirmationJson.literal(value, "targetSlot", Set.of("A", "B")),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(value);
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 将平台许可按同一严格合同序列化，不允许绕过范围校验。 */
    public byte[] encode(Permit permit) {
        if (permit == null) throw OtaConfirmationJson.invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", permit.contractVersion());
        value.put("permitId", String.valueOf(permit.permitId()));
        value.put("jobId", String.valueOf(permit.jobId()));
        value.put("attemptNo", (long) permit.attemptNo());
        value.put("authorizationId", String.valueOf(permit.authorizationId()));
        value.put("manifestSha256", permit.manifestSha256());
        value.put("bootId", String.valueOf(permit.bootId()));
        value.put("securityVersion", permit.securityVersion());
        value.put("artifactSha256", permit.artifactSha256());
        value.put("targetSlot", permit.targetSlot());
        value.put("expiresAt", permit.expiresAt());
        return decode(new OtaCanonicalJson().writeObject(value)).canonical();
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param permitId 协议permitId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param bootId 协议bootId字段
     * @param securityVersion 协议securityVersion字段
     * @param artifactSha256 协议artifactSha256字段
     * @param targetSlot 协议targetSlot字段
     * @param expiresAt 协议expiresAt字段
     */
    public record Permit(String contractVersion,
            UUID permitId,
            UUID jobId,
            int attemptNo,
            UUID authorizationId,
            String manifestSha256,
            UUID bootId,
            long securityVersion,
            String artifactSha256,
            String targetSlot,
            long expiresAt) { }
    /** 规范事实与完整摘要。
     * @param value 类型化协议
     * @param canonical 完整规范字节
     * @param sha256 规范SHA256
     */
    public record Decoded(Permit value, byte[] canonical, String sha256) {
        /** 防御复制输入。 */ public Decoded { canonical = canonical.clone(); }
        /** 防御复制输出。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
