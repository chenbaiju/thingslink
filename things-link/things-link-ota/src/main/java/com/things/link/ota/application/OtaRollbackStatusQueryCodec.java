package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 原子回退独立闭集协议，结构合法不代表设备动作获得授权。 */
public final class OtaRollbackStatusQueryCodec {
    /** 完整顶层白名单。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion",
            "queryId",
            "queryNonce",
            "operationId",
            "jobId",
            "attemptNo",
            "authorizationId",
            "manifestSha256",
            "operationSha256",
            "expiresAt");
    /** 限定原始十六KiB并恢复规范字节，不在词法层丢弃未知状态。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-rollback-status-query/v1");
        var typed = new Query(
                "tc-ota-rollback-status-query/v1",
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")),
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.hex(value, "operationSha256"),
                OtaConfirmationJson.integer(value, "expiresAt", 1, 253402300799L));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入同样执行完整词法与预算检查。 */
    public byte[] encode(Query input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 显式白名单编码，避免序列化实现细节。 */
    private static Map<String, Object> toMap(Query input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
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
     * @param authorizationId 协议authorizationId字段
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
            UUID authorizationId,
            String manifestSha256,
            String operationSha256,
            long expiresAt) { }
    /** 解码结果，规范字节不向调用方共享。
     * @param value 不可变协议
     * @param canonical 规范字节
     * @param sha256 完整摘要
     */
    public record Decoded(Query value, byte[] canonical, String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 输出字节防御复制。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
}
