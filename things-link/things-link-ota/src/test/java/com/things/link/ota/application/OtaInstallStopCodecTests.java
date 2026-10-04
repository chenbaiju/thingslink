package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 独立固定向量和不安全组合保留，软件合同不声明设备耐久性已验收。 */
class OtaInstallStopCodecTests {
    /** 严格规范JSON。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 独立操作协议。 */ private final OtaInstallStopOperationCodec operations = new OtaInstallStopOperationCodec();
    /** 独立结果协议。 */ private final OtaInstallStopOperationReportCodec reports = new OtaInstallStopOperationReportCodec();

    /** 各协议与独立Python生成字节和摘要相同，修改返回数组不影响事实。 */
    @Test void fixedGoldenVectorsRemainCanonicalAndDefensive() throws Exception {
        var operation = operations.decode(resource("install-stop-operation-v1.json"));
        assertGolden("install-stop-operation-v1", operation.canonical(), operation.sha256());
        assertThat(operations.encode(operation.value())).containsExactly(operation.canonical());
        assertThat(operation.value().authorizationIds()).isEmpty();
        byte[] exposed = operation.canonical(); exposed[0] = 0;
        assertThat(operation.canonical()[0]).isEqualTo((byte) '{');
        var report = reports.decode(resource("install-stop-operation-report-v1.json"));
        assertGolden("install-stop-operation-report-v1", report.canonical(), report.sha256());
        assertThat(reports.encode(report.value())).containsExactly(report.canonical());
        var queries = new OtaInstallStopStatusQueryCodec();
        var query = queries.decode(resource("install-stop-status-query-v1.json"));
        assertGolden("install-stop-status-query-v1", query.canonical(), query.sha256());
        assertThat(queries.encode(query.value())).containsExactly(query.canonical());
        var statuses = new OtaInstallStopStatusReportCodec();
        var status = statuses.decode(resource("install-stop-status-report-v1.json"));
        assertGolden("install-stop-status-report-v1", status.canonical(), status.sha256());
        assertThat(statuses.encode(status.value())).containsExactly(status.canonical());
        var baseline = new OtaInstallStopBaselineCodec().decode(resource("install-stop-baseline-v1.json"));
        assertGolden("install-stop-baseline-v1", baseline.canonical(), baseline.sha256());
    }

    /** UNKNOWN空日志和双方矛盾都要送达业务裁决，不能在词法层丢证据。 */
    @Test void emptyUnknownAndConflictingDurableFactsRemainRepresentable() throws Exception {
        var body = body("install-stop-operation-report-v1");
        var e = evidence(body); e.put("stopOperations", List.of()); e.put("journalRevision", 0L);
        body.put("status", "UNKNOWN");
        var unknown = reports.decode(json.writeObject(body));
        assertThat(unknown.value().evidence().stopOperations()).isEmpty();
        assertThat(unknown.value().evidence().installOperations()).isEmpty();
        body = body("install-stop-operation-report-v1"); e = evidence(body);
        e.put("installOperations", List.of(Map.of("authorizationId", UUID.randomUUID().toString(),
                "acceptedBootId", UUID.randomUUID().toString(), "acceptedRevision", 2L, "state", "INSTALL_ACCEPTED")));
        // 大于journal的接纳修订及两个方向均为待隔离证据，不在解析器假装可信或直接丢弃。
        var conflict = reports.decode(json.writeObject(body)).value().evidence();
        assertThat(conflict.stopOperations()).hasSize(1); assertThat(conflict.installOperations()).hasSize(1);
        assertThat(conflict.installOperations().getFirst().acceptedRevision()).isGreaterThan(conflict.journalRevision());
    }

    /** 原始预算、闭集、重复键、未知旧Profile和非法空值一致拒绝。 */
    @Test void rejectsOversizedOpenDuplicateAndForeignProfileInputs() throws Exception {
        byte[] original = resource("install-stop-operation-v1.json");
        byte[] oversized = new byte[16385]; java.util.Arrays.fill(oversized, (byte) ' ');
        System.arraycopy(original, 0, oversized, 0, original.length);
        assertThatThrownBy(() -> operations.decode(oversized)).isInstanceOf(IllegalArgumentException.class);
        var body = body("install-stop-operation-v1"); body.put("counter", 0L); rejectOperation(body);
        String duplicate = new String(original, StandardCharsets.UTF_8).replace("{", "{\"attemptNo\":1,");
        assertThatThrownBy(() -> operations.decode(duplicate.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        body = body("install-stop-operation-report-v1");
        evidence(body).put("atomicOperationProfile", "TC_OTA_AB_COMMIT_ROLLBACK_JOURNAL_V1"); rejectReport(body);
        body = body("install-stop-operation-report-v1"); evidence(body).put("unexpected", true); rejectReport(body);
        assertThatThrownBy(() -> reports.decode("null".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 所有身份和修订边界明确；列表最多一项，不能以多授权扩大语义。 */
    @Test void rejectsInvalidNumbersIdentifiersAndMultipleEntries() throws Exception {
        for (Object value : List.of(0L, 2147483648L, "1")) {
            var body = body("install-stop-operation-v1"); body.put("attemptNo", value); rejectOperation(body);
        }
        var body = body("install-stop-operation-v1");
        body.put("authorizationIds", List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString())); rejectOperation(body);
        body = body("install-stop-operation-v1"); body.put("manifestSha256", "A".repeat(64)); rejectOperation(body);
        body = body("install-stop-operation-v1"); body.put("expiresAt", 253402300800L); rejectOperation(body);
        body = body("install-stop-operation-report-v1"); evidence(body).put("journalRevision", 9007199254740992L); rejectReport(body);
        body = body("install-stop-operation-report-v1");
        var stop = new LinkedHashMap<>(OtaTrustBundleCodec.object(((List<?>) evidence(body).get("stopOperations")).getFirst()));
        stop.put("acceptedRevision", 0L); evidence(body).put("stopOperations", List.of(stop)); rejectReport(body);
    }

    /** 类型化列表输入和返回值不可变，旧UUID或空引用不能靠encode绕过。 */
    @Test void typedInputsDefendCollectionsAndStillValidate() throws Exception {
        var original = operations.decode(resource("install-stop-operation-v1.json")).value();
        var ids = new ArrayList<UUID>(); ids.add(UUID.randomUUID());
        var value = new OtaInstallStopOperationCodec.Operation(original.contractVersion(), original.operationId(),
                original.campaignId(), original.jobId(), original.attemptNo(), original.manifestSha256(), ids,
                original.stopBaselineSha256(), original.cancellationRevision(), original.expiresAt());
        ids.clear(); assertThat(value.authorizationIds()).hasSize(1);
        assertThatThrownBy(() -> value.authorizationIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(operations.decode(operations.encode(value)).value().authorizationIds()).hasSize(1);
        assertThatThrownBy(() -> operations.encode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaInstallStopOperationReportCodec.Evidence("a".repeat(64), "1.2.0", null,
                OtaInstallStopBaselineCodec.PROFILE, 0, "UNKNOWN", null, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 每次返回独立顶层，仅供反例修改。 */
    private Map<String, Object> body(String name) throws Exception {
        return new LinkedHashMap<>(json.parseObject(resource(name + ".json")));
    }
    /** 嵌套Map防御副本写回顶层，避免篡改不可变解码对象。 */
    private static Map<String, Object> evidence(Map<String, Object> body) {
        var value = new LinkedHashMap<>(OtaTrustBundleCodec.object(body.get("evidence")));
        body.put("evidence", value); return value;
    }
    /** 独立黄金结果不通过被测算法产生期望值。 */
    private static void assertGolden(String name, byte[] canonical, String sha) throws Exception {
        assertThat(canonical).containsExactly(resource(name + ".json"));
        assertThat(sha).isEqualTo(new String(resource(name + ".sha256"), StandardCharsets.US_ASCII).trim());
    }
    /** 公开词法向量不含私钥或设备凭据。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaInstallStopCodecTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("公开测试向量缺失");
            return input.readAllBytes();
        }
    }
    /** 无效操作必须明确拒绝。 */
    private void rejectOperation(Map<String, Object> value) {
        assertThatThrownBy(() -> operations.decode(json.writeObject(value))).isInstanceOf(IllegalArgumentException.class);
    }
    /** 无效报告不能进入业务。 */
    private void rejectReport(Map<String, Object> value) {
        assertThatThrownBy(() -> reports.decode(json.writeObject(value))).isInstanceOf(IllegalArgumentException.class);
    }
}
