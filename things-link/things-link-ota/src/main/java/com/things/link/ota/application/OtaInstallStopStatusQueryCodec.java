package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 安装前停止独立协议，解析合法不代表停止已获耐久接纳。 */
public final class OtaInstallStopStatusQueryCodec {
    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "queryId", "queryNonce", "operationId", "jobId", "attemptNo", "manifestSha256", "operationSha256", "expiresAt");
    /** 原始十六KiB、闭集和规范整数校验，保留结构合法的矛盾报告。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-install-stop-status-query/v1");
        var typed = new Query(
                "tc-ota-install-stop-status-query/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.hex(value, "operationSha256"),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入与网络输入使用相同验证。 */
    public byte[] encode(Query input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Query input) {
        var value = new LinkedHashMap<String, Object>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("operationSha256", input.operationSha256());
        value.put("expiresAt", input.expiresAt());
        return value;
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param queryId 协议queryId字段
     * @param queryNonce 协议queryNonce字段
     * @param operationId 协议operationId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param manifestSha256 协议manifestSha256字段
     * @param operationSha256 协议operationSha256字段
     * @param expiresAt 协议expiresAt字段
     */
    public record Query(String contractVersion,
            UUID queryId,
            UUID queryNonce,
            UUID operationId,
            UUID jobId,
            int attemptNo,
            String manifestSha256,
            String operationSha256,
            long expiresAt) {  }
    /** 不可变协议字段。
     * @param value 协议value字段
     * @param canonical 协议canonical字段
     * @param sha256 协议sha256字段
     */
    public record Decoded(Query value,
            byte[] canonical,
            String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 返回独立规范字节。 */
        @Override public byte[] canonical() { return canonical.clone(); }
 }
}
