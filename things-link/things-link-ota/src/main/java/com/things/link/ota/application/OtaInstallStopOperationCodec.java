package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/** 安装前停止独立协议，解析合法不代表停止已获耐久接纳。 */
public final class OtaInstallStopOperationCodec {
    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "operationId", "campaignId", "jobId", "attemptNo", "manifestSha256", "authorizationIds", "stopBaselineSha256", "cancellationRevision", "expiresAt");
    /** 原始十六KiB、闭集和规范整数校验，保留结构合法的矛盾报告。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-install-stop-operation/v1");
        var typed = new Operation(
                "tc-ota-install-stop-operation/v1",
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("campaignId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaInstallStopJson.ids(value.get("authorizationIds")),
                OtaConfirmationJson.hex(value, "stopBaselineSha256"),
                OtaConfirmationJson.integer(value, "cancellationRevision", 0, 9007199254740991L),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入与网络输入使用相同验证。 */
    public byte[] encode(Operation input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Operation input) {
        var value = new LinkedHashMap<String, Object>();
        value.put("contractVersion", input.contractVersion());
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("campaignId", String.valueOf(input.campaignId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("authorizationIds", input.authorizationIds().stream().map(UUID::toString).toList());
        value.put("stopBaselineSha256", input.stopBaselineSha256());
        value.put("cancellationRevision", input.cancellationRevision());
        value.put("expiresAt", input.expiresAt());
        return value;
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param operationId 协议operationId字段
     * @param campaignId 协议campaignId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param manifestSha256 协议manifestSha256字段
     * @param authorizationIds 协议authorizationIds字段
     * @param stopBaselineSha256 协议stopBaselineSha256字段
     * @param cancellationRevision 协议cancellationRevision字段
     * @param expiresAt 协议expiresAt字段
     */
    public record Operation(String contractVersion,
            UUID operationId,
            UUID campaignId,
            UUID jobId,
            int attemptNo,
            String manifestSha256,
            List<UUID> authorizationIds,
            String stopBaselineSha256,
            long cancellationRevision,
            long expiresAt) {
        /** 该作业全部下载授权id，按id升序与DB函数array_agg(id ORDER BY id)一致；规范线格式当前仅允许≤1项。 */
        public Operation {
            if (authorizationIds == null || authorizationIds.stream().anyMatch(java.util.Objects::isNull)) throw OtaConfirmationJson.invalid();
            authorizationIds = List.copyOf(authorizationIds);
        }
 }
    /** 不可变协议字段。
     * @param value 协议value字段
     * @param canonical 协议canonical字段
     * @param sha256 协议sha256字段
     */
    public record Decoded(Operation value,
            byte[] canonical,
            String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 返回独立规范字节。 */
        @Override public byte[] canonical() { return canonical.clone(); }
 }
}
