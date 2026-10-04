package com.things.link.ota.application;

import com.things.link.ota.application.OtaInstallStopOperationReportCodec.Evidence;
import com.things.link.ota.application.OtaInstallStopOperationReportCodec.InstallOperation;
import com.things.link.ota.application.OtaInstallStopOperationReportCodec.StopOperation;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Hardware;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 安装停止的独立嵌套词法，不扩展既有提交回退Profile。 */
final class OtaInstallStopJson {
    /** 跨语言整数边界。 */ private static final long MAX = 9007199254740991L;
    /** 不创建可绕过资格的组件。 */ private OtaInstallStopJson() { }
    /** 零或一个值；空数组有效，空引用或多值无效。 */
    private static List<?> items(Object input) {
        if (!(input instanceof List<?> values) || values.size() > 1) throw OtaConfirmationJson.invalid();
        return values;
    }
    /** 未授权阶段允许空数组，不凭空补造授权。 */
    static List<UUID> ids(Object input) {
        var result = new ArrayList<UUID>();
        for (Object value : items(input)) result.add(OtaTrustBundleCodec.uuid(value));
        return List.copyOf(result);
    }
    /** 保留双方接纳和修订不一致等结构合法证据，交业务安全裁决。 */
    static Evidence evidence(Object input) {
        var value = OtaTrustBundleCodec.object(input);
        OtaTrustBundleCodec.closed(value, Set.of("stopBaselineSha256", "bootloaderVersion", "hardware",
                "atomicOperationProfile", "journalRevision", "writeState", "stopOperations", "installOperations"));
        var h = OtaTrustBundleCodec.object(value.get("hardware"));
        OtaTrustBundleCodec.closed(h, Set.of("model", "boardRevision"));
        var hardware = new Hardware(OtaTrustBundleCodec.text(h.get("model"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                OtaConfirmationJson.integer(h, "boardRevision", 0, MAX));
        var stops = new ArrayList<StopOperation>();
        for (Object item : items(value.get("stopOperations"))) {
            var op = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(op, Set.of("operationId", "operationSha256", "acceptedBootId", "acceptedRevision", "state"));
            stops.add(new StopOperation(OtaTrustBundleCodec.uuid(op.get("operationId")),
                    OtaConfirmationJson.hex(op, "operationSha256"), OtaTrustBundleCodec.uuid(op.get("acceptedBootId")),
                    OtaConfirmationJson.integer(op, "acceptedRevision", 1, MAX),
                    OtaConfirmationJson.literal(op, "state", Set.of("STOPPED"))));
        }
        var installs = new ArrayList<InstallOperation>();
        for (Object item : items(value.get("installOperations"))) {
            var op = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(op, Set.of("authorizationId", "acceptedBootId", "acceptedRevision", "state"));
            installs.add(new InstallOperation(OtaTrustBundleCodec.uuid(op.get("authorizationId")),
                    OtaTrustBundleCodec.uuid(op.get("acceptedBootId")), OtaConfirmationJson.integer(op, "acceptedRevision", 1, MAX),
                    OtaConfirmationJson.literal(op, "state", Set.of("INSTALL_ACCEPTED"))));
        }
        return new Evidence(OtaConfirmationJson.hex(value, "stopBaselineSha256"),
                OtaRollbackPreflightJson.version(value.get("bootloaderVersion")), hardware,
                OtaConfirmationJson.literal(value, "atomicOperationProfile", Set.of(OtaInstallStopBaselineCodec.PROFILE)),
                OtaConfirmationJson.integer(value, "journalRevision", 0, MAX),
                OtaConfirmationJson.literal(value, "writeState", Set.of("QUIESCENT", "WRITING", "UNKNOWN")), stops, installs);
    }
    /** 只序列化明确证据字段，不将对象实现细节带入规范合同。 */
    static Map<String, Object> encodeEvidence(Evidence input) {
        if (input == null || input.hardware() == null) throw OtaConfirmationJson.invalid();
        var value = new LinkedHashMap<String, Object>();
        value.put("stopBaselineSha256", input.stopBaselineSha256());
        value.put("bootloaderVersion", input.bootloaderVersion());
        var hardware = new LinkedHashMap<String, Object>();
        hardware.put("model", input.hardware().model()); hardware.put("boardRevision", input.hardware().boardRevision());
        value.put("hardware", hardware); value.put("atomicOperationProfile", input.atomicOperationProfile());
        value.put("journalRevision", input.journalRevision()); value.put("writeState", input.writeState());
        value.put("stopOperations", input.stopOperations().stream().map(OtaInstallStopJson::encodeStop).toList());
        value.put("installOperations", input.installOperations().stream().map(OtaInstallStopJson::encodeInstall).toList());
        return value;
    }
    /** 固定停止日志项，字段空值仍由规范JSON拒绝。 */
    private static Map<String, Object> encodeStop(StopOperation input) {
        var value = new LinkedHashMap<String, Object>();
        value.put("operationId", String.valueOf(input.operationId())); value.put("operationSha256", input.operationSha256());
        value.put("acceptedBootId", String.valueOf(input.acceptedBootId())); value.put("acceptedRevision", input.acceptedRevision());
        value.put("state", input.state()); return value;
    }
    /** 固定安装接纳项，不能把自报状态伪装成平台授权事实。 */
    private static Map<String, Object> encodeInstall(InstallOperation input) {
        var value = new LinkedHashMap<String, Object>();
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("acceptedBootId", String.valueOf(input.acceptedBootId())); value.put("acceptedRevision", input.acceptedRevision());
        value.put("state", input.state()); return value;
    }
}
