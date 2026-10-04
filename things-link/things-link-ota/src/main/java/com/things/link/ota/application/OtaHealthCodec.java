package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立认证健康确认协议的有界规范合同。 */
public final class OtaHealthCodec {
    /** 精确顶层字段，不接受未来扩展。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "authorizationId", "manifestSha256", "healthSeq", "bootId", "evidence");
    /** 验证词法和闭集并冻结规范字节，不裁决设备安全事实。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-health/v1");
        if (!(value.get("evidence") instanceof Map<?, ?>)) throw OtaConfirmationJson.invalid();
        @SuppressWarnings("unchecked") Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) value.get("evidence"));
        long uptime = OtaConfirmationJson.integer(e, "uptimeMillis", 0, 9007199254740991L);
        long healthy = OtaConfirmationJson.integer(e, "healthyForMillis", 0, uptime);
        e.remove("uptimeMillis");
        e.remove("healthyForMillis");
        var evidence = new HealthEvidence(OtaConfirmationJson.evidence(e), uptime, healthy);
        var typed = new Health("tc-ota-health/v1",
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.integer(value, "healthSeq", 1, 9007199254740991L),
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
     * @param healthSeq 协议healthSeq字段
     * @param bootId 协议bootId字段
     * @param evidence 协议evidence字段
     */
    public record Health(String contractVersion,
            UUID jobId,
            int attemptNo,
            UUID authorizationId,
            String manifestSha256,
            long healthSeq,
            UUID bootId,
            HealthEvidence evidence) { }
    /** 健康证据在JSON内平铺，Java组合保持目标证据语义独立。
     * @param target 完整十九字段目标观察
     * @param uptimeMillis 当前启动持续毫秒
     * @param healthyForMillis 连续健康毫秒
     */
    public record HealthEvidence(OtaJobProgressCodec.Evidence target, long uptimeMillis, long healthyForMillis) { }
    /** 规范事实与完整摘要。
     * @param value 类型化协议
     * @param canonical 完整规范字节
     * @param sha256 规范SHA256
     */
    public record Decoded(Health value, byte[] canonical, String sha256) {
        /** 防御复制输入。 */ public Decoded { canonical = canonical.clone(); }
        /** 防御复制输出。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
