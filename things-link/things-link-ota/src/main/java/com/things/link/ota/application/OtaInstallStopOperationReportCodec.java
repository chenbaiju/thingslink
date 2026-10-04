package com.things.link.ota.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Hardware;

/** 安装前停止独立协议，解析合法不代表停止已获耐久接纳。 */
public final class OtaInstallStopOperationReportCodec {
    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "operationId", "jobId", "attemptNo", "manifestSha256", "operationSha256", "reportId", "reportSeq", "bootId", "status", "evidence");
    /** 原始十六KiB、闭集和规范整数校验，保留结构合法的矛盾报告。 */
    public Decoded decode(byte[] input) {
        var value = OtaConfirmationJson.parse(input, FIELDS, "tc-ota-install-stop-operation-report/v1");
        var typed = new Report(
                "tc-ota-install-stop-operation-report/v1",
                OtaTrustBundleCodec.uuid(value.get("operationId")),
                OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) OtaConfirmationJson.integer(value, "attemptNo", 1, Integer.MAX_VALUE),
                OtaConfirmationJson.hex(value, "manifestSha256"),
                OtaConfirmationJson.hex(value, "operationSha256"),
                OtaTrustBundleCodec.uuid(value.get("reportId")),
                OtaConfirmationJson.integer(value, "reportSeq", 1, 9007199254740991L),
                OtaTrustBundleCodec.uuid(value.get("bootId")),
                OtaConfirmationJson.literal(value, "status", Set.of("STOPPED", "INSTALL_WON", "REFUSED", "UNKNOWN")),
                OtaInstallStopJson.evidence(value.get("evidence")));
        byte[] canonical = new OtaCanonicalJson().writeObject(toMap(typed));
        return new Decoded(typed, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 类型化输入与网络输入使用相同验证。 */
    public byte[] encode(Report input) {
        if (input == null) throw OtaConfirmationJson.invalid();
        return decode(new OtaCanonicalJson().writeObject(toMap(input))).canonical();
    }
    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Report input) {
        var value = new LinkedHashMap<String, Object>();
        value.put("contractVersion", input.contractVersion());
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("operationSha256", input.operationSha256());
        value.put("reportId", String.valueOf(input.reportId()));
        value.put("reportSeq", input.reportSeq());
        value.put("bootId", String.valueOf(input.bootId()));
        value.put("status", input.status());
        value.put("evidence", OtaInstallStopJson.encodeEvidence(input.evidence()));
        return value;
    }
    /** 不可变协议字段。
     * @param contractVersion 协议contractVersion字段
     * @param operationId 协议operationId字段
     * @param jobId 协议jobId字段
     * @param attemptNo 协议attemptNo字段
     * @param manifestSha256 协议manifestSha256字段
     * @param operationSha256 协议operationSha256字段
     * @param reportId 协议reportId字段
     * @param reportSeq 协议reportSeq字段
     * @param bootId 协议bootId字段
     * @param status 协议status字段
     * @param evidence 协议evidence字段
     */
    public record Report(String contractVersion,
            UUID operationId,
            UUID jobId,
            int attemptNo,
            String manifestSha256,
            String operationSha256,
            UUID reportId,
            long reportSeq,
            UUID bootId,
            String status,
            Evidence evidence) {  }
    /** 不可变协议字段。
     * @param stopBaselineSha256 协议stopBaselineSha256字段
     * @param bootloaderVersion 协议bootloaderVersion字段
     * @param hardware 协议hardware字段
     * @param atomicOperationProfile 协议atomicOperationProfile字段
     * @param journalRevision 协议journalRevision字段
     * @param writeState 协议writeState字段
     * @param stopOperations 协议stopOperations字段
     * @param installOperations 协议installOperations字段
     */
    public record Evidence(String stopBaselineSha256,
            String bootloaderVersion,
            Hardware hardware,
            String atomicOperationProfile,
            long journalRevision,
            String writeState,
            List<StopOperation> stopOperations,
            List<InstallOperation> installOperations) {
        /** 日志列表不可变，双方同时出现由业务判冲突。 */
        public Evidence {
            if (stopOperations == null || installOperations == null
                    || stopOperations.stream().anyMatch(java.util.Objects::isNull)
                    || installOperations.stream().anyMatch(java.util.Objects::isNull)) throw OtaConfirmationJson.invalid();
            stopOperations = List.copyOf(stopOperations); installOperations = List.copyOf(installOperations);
        }
 }
    /** 不可变协议字段。
     * @param operationId 协议operationId字段
     * @param operationSha256 协议operationSha256字段
     * @param acceptedBootId 协议acceptedBootId字段
     * @param acceptedRevision 协议acceptedRevision字段
     * @param state 协议state字段
     */
    public record StopOperation(UUID operationId,
            String operationSha256,
            UUID acceptedBootId,
            long acceptedRevision,
            String state) {  }
    /** 不可变协议字段。
     * @param authorizationId 协议authorizationId字段
     * @param acceptedBootId 协议acceptedBootId字段
     * @param acceptedRevision 协议acceptedRevision字段
     * @param state 协议state字段
     */
    public record InstallOperation(UUID authorizationId,
            UUID acceptedBootId,
            long acceptedRevision,
            String state) {  }
    /** 不可变协议字段。
     * @param value 协议value字段
     * @param canonical 协议canonical字段
     * @param sha256 协议sha256字段
     */
    public record Decoded(Report value,
            byte[] canonical,
            String sha256) {
        /** 输入字节防御复制。 */
        public Decoded { canonical = canonical.clone(); }
        /** 返回独立规范字节。 */
        @Override public byte[] canonical() { return canonical.clone(); }
 }
}
