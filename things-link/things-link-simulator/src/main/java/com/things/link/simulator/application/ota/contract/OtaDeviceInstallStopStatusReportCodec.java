package com.things.link.simulator.application.ota.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧安装前停止状态报告合同编解码：{@code tc-ota-install-stop-status-report/v1}。
 *
 * <p>在操作报告的十一字段之外增加 {@code queryId}/{@code queryNonce}，把报告绑定到一次只读查询。
 * 字段与平台 {@code OtaInstallStopStatusReportCodec} 完全一致，证据词法复用
 * {@link OtaDeviceInstallStopReportCodec.Evidence}。</p>
 *
 * <p>查询不刷新原操作或作业期限；本类只编码字节，不做任何期限推进或状态迁移。</p>
 */
public final class OtaDeviceInstallStopStatusReportCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-install-stop-status-report/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "operationId", "jobId", "attemptNo",
            "manifestSha256", "operationSha256", "reportId", "reportSeq", "bootId", "status", "evidence",
            "queryId", "queryNonce");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceInstallStopStatusReportCodec() {
    }

    /**
     * 把类型化状态报告编码为平台可解码的规范字节。
     *
     * @param value 状态报告
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、查询身份非法或版本错误时
     */
    public static byte[] encode(Report value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析状态报告。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化报告、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、枚举、边界或规范形态不符时
     */
    public static Decoded decode(byte[] input) {
        try {
            if (input == null || input.length == 0 || input.length > MAX_BYTES) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> fields = JSON.parseObject(input);
            OtaContractFields.closed(fields, FIELDS);
            if (!CONTRACT_VERSION.equals(fields.get("contractVersion"))) {
                throw OtaContractFields.invalid();
            }
            Report report = new Report(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "operationId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    OtaContractFields.hex(fields, "manifestSha256"),
                    OtaContractFields.hex(fields, "operationSha256"),
                    OtaContractFields.uuid(fields, "reportId"),
                    OtaContractFields.integer(fields, "reportSeq", 1, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.uuid(fields, "bootId"),
                    OtaContractFields.literal(fields, "status", Set.of("STOPPED", "INSTALL_WON", "REFUSED", "UNKNOWN")),
                    OtaDeviceInstallStopReportCodec.evidence(fields.get("evidence")),
                    OtaContractFields.uuid(fields, "queryId"),
                    OtaContractFields.uuid(fields, "queryNonce"));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(report, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段。 */
    private static Map<String, Object> toMap(Report input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
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
        value.put("evidence", OtaDeviceInstallStopReportCodec.encodeEvidence(input.evidence()));
        value.put("queryId", String.valueOf(input.queryId()));
        value.put("queryNonce", String.valueOf(input.queryNonce()));
        return value;
    }

    /** 不可变协议字段。
     *
     * @param contractVersion 协议版本
     * @param operationId 停止操作身份
     * @param jobId 作业身份
     * @param attemptNo 尝试号
     * @param manifestSha256 清单摘要
     * @param operationSha256 操作摘要
     * @param reportId 报告身份
     * @param reportSeq 报告序号
     * @param bootId 当前启动身份
     * @param status 停止状态
     * @param evidence 停止证据
     * @param queryId 查询身份
     * @param queryNonce 查询随机数
     */
    public record Report(String contractVersion, UUID operationId, UUID jobId, int attemptNo, String manifestSha256,
            String operationSha256, UUID reportId, long reportSeq, UUID bootId, String status,
            OtaDeviceInstallStopReportCodec.Evidence evidence, UUID queryId, UUID queryNonce) {
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化报告
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Report value, byte[] canonical, String sha256) {

        /** 防止调用者修改内部规范字节。 */
        public Decoded {
            canonical = canonical.clone();
        }

        /**
         * @return 独立字节副本
         */
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }
    }
}
