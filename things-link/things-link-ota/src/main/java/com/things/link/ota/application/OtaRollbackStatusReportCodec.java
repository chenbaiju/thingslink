package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.things.link.ota.application.OtaRollbackOperationReportCodec.Evidence;

/** 原子回退独立闭集协议，结构合法不代表设备动作获得授权。 */
public final class OtaRollbackStatusReportCodec {
    /** 完整顶层白名单。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion",
            "operationId",
            "jobId",
            "attemptNo",
            "authorizationId",
            "manifestSha256",
            "operationSha256",
            "reportId",
            "reportSeq",
            "bootId",
            "status",
            "evidence",
            "queryId",
            "queryNonce");
    /** 限定原始十六KiB并恢复规范字节，不在词法层丢弃未知状态。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-rollback-status-report/v1");
        var typed = new Report(
                "tc-ota-rollback-status-report/v1",
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.hex(value, "operationSha256"),
                OtaTrustBundleCodec.uuid(value.get("reportId")),
                OtaConfirmationJson.integer(value, "reportSeq", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                OtaConfirmationJson.literal(value, "status", Set.of("ACCEPTED", "ROLLING_BACK", "ROLLED_BACK", "COMMIT_WON", "REFUSED", "UNKNOWN")),
                OtaRollbackExecutionJson.evidence(value.get("evidence")),
                OtaTrustBundleCodec.uuid(value.get("queryId")),
                OtaTrustBundleCodec.uuid(value.get("queryNonce")));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入同样执行完整词法与预算检查。 */
    public byte[] encode(Report input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 显式白名单编码，避免序列化实现细节。 */
    private static Map<String, Object> toMap(Report input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("manifestSha256", input.manifestSha256());
        value.put("operationSha256", input.operationSha256());
        value.put("reportId", String.valueOf(input.reportId()));
        value.put("reportSeq", input.reportSeq());
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("status", input.status());
        value.put("evidence", OtaRollbackExecutionJson.encode(input.evidence()));
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        return value;
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param operationId 协议operationId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param authorizationId 协议authorizationId字段
     * @param manifestSha256 协议manifestSha256字段
     * @param operationSha256 协议operationSha256字段
     * @param reportId 协议reportId字段
     * @param reportSeq 协议reportSeq字段
     * @param bootId 协议bootId字段
     * @param status 协议status字段
     * @param evidence 协议evidence字段
     * @param queryId 协议queryId字段
     * @param queryNonce 协议queryNonce字段
     */
    public record Report(String contractVersion,
            UUID operationId,
            UUID jobId,
            int attemptNo,
            UUID authorizationId,
            String manifestSha256,
            String operationSha256,
            UUID reportId,
            long reportSeq,
            UUID bootId,
            String status,
            Evidence evidence,
            UUID queryId,
            UUID queryNonce) { }
    /** 解码结果，规范字节不向调用方共享。
     * @param value 不可变协议
     * @param canonical 规范字节
     * @param sha256 完整摘要
     */
    public record Decoded(Report value, byte[] canonical, String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 输出字节防御复制。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
}
