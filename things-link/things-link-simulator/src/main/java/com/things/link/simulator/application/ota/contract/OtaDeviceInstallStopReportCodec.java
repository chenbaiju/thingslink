package com.things.link.simulator.application.ota.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧安装前停止操作报告合同编解码：{@code tc-ota-install-stop-operation-report/v1}。
 *
 * <p>字段与平台 {@code OtaInstallStopOperationReportCodec} 完全一致。报告绑定停止操作身份、
 * 清单摘要、操作摘要、报告序号、boot 身份，以及一份结构化的停止/安装日志证据。</p>
 *
 * <p><b>为什么证据里允许同时出现停止与安装日志：</b>那是结构合法但语义冲突的事实证明
 * （「停止先赢」与「安装先赢」同时成立），必须原样上报给平台安全裁决，不能在设备侧被静默合并。
 * 本类因此只做词法校验，不替平台判定谁先赢。</p>
 *
 * <p>{@code atomicOperationProfile} 固定为 {@code TC_OTA_INSTALL_STOP_JOURNAL_V1}，
 * 与平台 {@code OtaInstallStopBaselineCodec.PROFILE} 一致。</p>
 */
public final class OtaDeviceInstallStopReportCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-install-stop-operation-report/v1";

    /** 平台对该合同冻结的原始字节上限。 */
    public static final int MAX_BYTES = 16_384;

    /** 平台冻结的原子操作 Profile。 */
    public static final String ATOMIC_OPERATION_PROFILE = "TC_OTA_INSTALL_STOP_JOURNAL_V1";

    /** 严格顶层字段集。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "operationId", "jobId", "attemptNo",
            "manifestSha256", "operationSha256", "reportId", "reportSeq", "bootId", "status", "evidence");

    /** 证据闭集。 */
    private static final Set<String> EVIDENCE_FIELDS = Set.of("stopBaselineSha256", "bootloaderVersion", "hardware",
            "atomicOperationProfile", "journalRevision", "writeState", "stopOperations", "installOperations");

    /** 平台接受的三轴语义版本。 */
    private static final String VERSION_PATTERN =
            "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})";

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 工具类不允许实例化。 */
    private OtaDeviceInstallStopReportCodec() {
    }

    /**
     * 把类型化报告编码为平台可解码的规范字节。
     *
     * @param value 停止操作报告
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 字段缺失、列表超一项或版本错误时
     */
    public static byte[] encode(Report value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析停止操作报告。
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
                    evidence(fields.get("evidence")));
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
        value.put("evidence", encodeEvidence(input.evidence()));
        return value;
    }

    /** 解析停止证据闭集；状态报告复用同一词法。 */
    static Evidence evidence(Object input) {
        Map<String, Object> value = OtaContractFields.object(input);
        OtaContractFields.closed(value, EVIDENCE_FIELDS);
        Map<String, Object> hardwareFields = OtaContractFields.object(value.get("hardware"));
        OtaContractFields.closed(hardwareFields, Set.of("model", "boardRevision"));
        Hardware hardware = new Hardware(
                OtaContractFields.text(hardwareFields, "model", "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                OtaContractFields.integer(hardwareFields, "boardRevision", 0, OtaContractFields.MAX_SAFE_INTEGER));
        List<StopOperation> stopOperations = new ArrayList<>();
        for (Object item : OtaContractFields.items(value.get("stopOperations"))) {
            Map<String, Object> operation = OtaContractFields.object(item);
            OtaContractFields.closed(operation,
                    Set.of("operationId", "operationSha256", "acceptedBootId", "acceptedRevision", "state"));
            stopOperations.add(new StopOperation(
                    OtaContractFields.uuid(operation, "operationId"),
                    OtaContractFields.hex(operation, "operationSha256"),
                    OtaContractFields.uuid(operation, "acceptedBootId"),
                    OtaContractFields.integer(operation, "acceptedRevision", 1, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.literal(operation, "state", Set.of("STOPPED"))));
        }
        List<InstallOperation> installOperations = new ArrayList<>();
        for (Object item : OtaContractFields.items(value.get("installOperations"))) {
            Map<String, Object> operation = OtaContractFields.object(item);
            OtaContractFields.closed(operation,
                    Set.of("authorizationId", "acceptedBootId", "acceptedRevision", "state"));
            installOperations.add(new InstallOperation(
                    OtaContractFields.uuid(operation, "authorizationId"),
                    OtaContractFields.uuid(operation, "acceptedBootId"),
                    OtaContractFields.integer(operation, "acceptedRevision", 1, OtaContractFields.MAX_SAFE_INTEGER),
                    OtaContractFields.literal(operation, "state", Set.of("INSTALL_ACCEPTED"))));
        }
        return new Evidence(OtaContractFields.hex(value, "stopBaselineSha256"),
                version(value.get("bootloaderVersion")),
                hardware,
                OtaContractFields.literal(value, "atomicOperationProfile", Set.of(ATOMIC_OPERATION_PROFILE)),
                OtaContractFields.integer(value, "journalRevision", 0, OtaContractFields.MAX_SAFE_INTEGER),
                OtaContractFields.literal(value, "writeState", Set.of("QUIESCENT", "WRITING", "UNKNOWN")),
                List.copyOf(stopOperations),
                List.copyOf(installOperations));
    }

    /** 只序列化明确证据字段。 */
    static Map<String, Object> encodeEvidence(Evidence input) {
        if (input == null || input.hardware() == null || input.stopOperations() == null
                || input.installOperations() == null) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stopBaselineSha256", input.stopBaselineSha256());
        value.put("bootloaderVersion", input.bootloaderVersion());
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("model", input.hardware().model());
        hardware.put("boardRevision", input.hardware().boardRevision());
        value.put("hardware", hardware);
        value.put("atomicOperationProfile", input.atomicOperationProfile());
        value.put("journalRevision", input.journalRevision());
        value.put("writeState", input.writeState());
        value.put("stopOperations", input.stopOperations().stream().map(OtaDeviceInstallStopReportCodec::encodeStop).toList());
        value.put("installOperations",
                input.installOperations().stream().map(OtaDeviceInstallStopReportCodec::encodeInstall).toList());
        return value;
    }

    /** 固定停止日志项。 */
    private static Map<String, Object> encodeStop(StopOperation input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operationId", String.valueOf(input.operationId()));
        value.put("operationSha256", input.operationSha256());
        value.put("acceptedBootId", String.valueOf(input.acceptedBootId()));
        value.put("acceptedRevision", input.acceptedRevision());
        value.put("state", input.state());
        return value;
    }

    /** 固定安装接纳项，不能把自报状态伪装成平台授权事实。 */
    private static Map<String, Object> encodeInstall(InstallOperation input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("acceptedBootId", String.valueOf(input.acceptedBootId()));
        value.put("acceptedRevision", input.acceptedRevision());
        value.put("state", input.state());
        return value;
    }

    /** 保持三轴 int 边界语义的版本文本。 */
    private static String version(Object value) {
        String text = OtaContractFields.text(value, VERSION_PATTERN);
        try {
            for (String axis : text.split("\\.")) {
                Integer.parseInt(axis);
            }
        } catch (NumberFormatException exception) {
            throw OtaContractFields.invalid();
        }
        return text;
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
     */
    public record Report(String contractVersion, UUID operationId, UUID jobId, int attemptNo, String manifestSha256,
            String operationSha256, UUID reportId, long reportSeq, UUID bootId, String status, Evidence evidence) {
    }

    /** 不可变停止证据。
     *
     * @param stopBaselineSha256 停止基线摘要
     * @param bootloaderVersion bootloader 版本
     * @param hardware 硬件身份
     * @param atomicOperationProfile 原子操作 Profile
     * @param journalRevision 日志修订
     * @param writeState 写入状态
     * @param stopOperations 停止日志（最多一项）
     * @param installOperations 安装日志（最多一项）
     */
    public record Evidence(String stopBaselineSha256, String bootloaderVersion, Hardware hardware,
            String atomicOperationProfile, long journalRevision, String writeState,
            List<StopOperation> stopOperations, List<InstallOperation> installOperations) {

        /** 列表不可变；两方同时出现由平台业务判冲突。 */
        public Evidence {
            if (stopOperations == null || installOperations == null
                    || stopOperations.stream().anyMatch(Objects::isNull)
                    || installOperations.stream().anyMatch(Objects::isNull)) {
                throw OtaContractFields.invalid();
            }
            stopOperations = List.copyOf(stopOperations);
            installOperations = List.copyOf(installOperations);
        }
    }

    /** 不可变硬件身份。
     *
     * @param model 硬件型号
     * @param boardRevision 板修订号
     */
    public record Hardware(String model, long boardRevision) {
    }

    /** 已接纳的停止日志项。
     *
     * @param operationId 停止操作身份
     * @param operationSha256 操作摘要
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的修订
     * @param state 固定为 STOPPED
     */
    public record StopOperation(UUID operationId, String operationSha256, UUID acceptedBootId,
            long acceptedRevision, String state) {
    }

    /** 已接纳的安装日志项。
     *
     * @param authorizationId 平台真实授权
     * @param acceptedBootId 首次接受时的启动身份
     * @param acceptedRevision 首次接受时的修订
     * @param state 固定为 INSTALL_ACCEPTED
     */
    public record InstallOperation(UUID authorizationId, UUID acceptedBootId, long acceptedRevision, String state) {
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
