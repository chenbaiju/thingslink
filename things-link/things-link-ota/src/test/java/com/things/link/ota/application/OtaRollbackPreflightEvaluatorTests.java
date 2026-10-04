package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实词法合同与独立来源事实验证只读准备资格，不使用权限mock。 */
class OtaRollbackPreflightEvaluatorTests {
    /** 匹配原槽只能准备，原许可缺失或存在均仍需未来原子围栏。 */
    @Test void exactOriginalSlotOnlyPreparesWithMandatoryFutureFence() throws Exception {
        var f = fixture();
        var result = evaluate(f, f.report(), f.current(), Optional.empty(), OptionalLong.empty(), permits(f));
        assertEquals("PREPARABLE", result.disposition());
        assertEquals("ATOMIC_FENCE_STILL_REQUIRED", result.reason());
        var fields = reportFields();
        journal(fields).put("commitOperations", List.of());
        assertEquals(result, evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), List.of()));
        slot(fields, "B").put("health", "UNHEALTHY");
        assertEquals(result, evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), List.of()));
    }
    /** 原槽完整性、可启动和独立bootloader验证缺一都不准备。 */
    @ParameterizedTest
    @ValueSource(strings = {"integrity", "bootable", "bootloaderVerified", "health", "artifactSha256"})
    void invalidOriginalSlotCannotPrepare(String field) throws Exception {
        var f = fixture(); var fields = reportFields();
        Object value = switch (field) {
            case "integrity" -> "INVALID";
            case "health" -> "UNHEALTHY";
            case "artifactSha256" -> "f".repeat(64);
            default -> false;
        };
        slot(fields, "A").put(field, value);
        assertNotEquals("PREPARABLE", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).disposition());
    }
    /** 尚在写入或日志不确定时不能用默认值放行。 */
    @Test void unknownWriterAndJournalRemainUnknown() throws Exception {
        var f = fixture(); var fields = reportFields();
        evidence(fields).put("writeState", "UNKNOWN");
        assertEquals("UNKNOWN", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).disposition());
        evidence(fields).put("writeState", "QUIESCENT"); journal(fields).put("state", "UNKNOWN");
        assertEquals("UNKNOWN", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).disposition());
        journal(fields).put("state", "IDLE"); evidence(fields).put("writeState", "WRITING");
        assertEquals("INELIGIBLE", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).disposition());
    }
    /** 已接纳、已提交和未知提交操作都不能允许后续回退准备。 */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COMMITTED", "UNKNOWN"})
    void unsafeCommitOperationNeverPrepares(String state) throws Exception {
        var f = fixture(); var fields = reportFields();
        journal(fields).put("commitOperations", List.of(Map.of("permitId", permits(f).getFirst().toString(), "state", state)));
        assertNotEquals("PREPARABLE", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).disposition());
        journal(fields).put("commitOperations", List.of(Map.of("permitId", UUID.randomUUID().toString(), "state", "NOT_ACCEPTED")));
        assertEquals("COMMIT_JOURNAL_PERMIT_MISMATCH", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).reason());
    }
    /** 历史较高观察和平台耐久下限不因新报告较低而遗忘。 */
    @Test void reportedAndKnownFloorsCannotBeErased() throws Exception {
        var f = fixture();
        assertEquals("COMMITTED_COUNTER_CHANGED", evaluate(f, f.report(), f.current(), Optional.empty(), OptionalLong.of(2), permits(f)).reason());
        var high = new OtaDeviceCommitPort.SecurityFloor(2, "b".repeat(64), UUID.randomUUID());
        assertEquals("KNOWN_SECURITY_FLOOR_CONFLICT", evaluate(f, f.report(), f.current(), Optional.of(high), OptionalLong.empty(), permits(f)).reason());
        var wrongArtifact = new OtaDeviceCommitPort.SecurityFloor(1, "f".repeat(64), UUID.randomUUID());
        assertEquals("KNOWN_SECURITY_FLOOR_CONFLICT", evaluate(f, f.report(), f.current(), Optional.of(wrongArtifact), OptionalLong.empty(), permits(f)).reason());
        var fields = reportFields(); fields.put("committedSecurityVersion", 2L);
        assertEquals("COMMITTED_COUNTER_CHANGED", evaluate(f, report(fields), f.current(), Optional.empty(), OptionalLong.empty(), permits(f)).reason());
    }
    /** 外部改绑、不同硬件及受控范围外bootloader均拒绝。 */
    @Test void authoritativeModelHardwareAndBootloaderMustMatch() throws Exception {
        var f = fixture(); var current = f.current();
        var changed = new OtaDeviceIdentity(current.tenantId(), current.projectId(), current.deviceId(), current.deviceTypeId(),
                current.productKey(), current.credentialVersion(), UUID.randomUUID(), current.schemaDigestAlgorithm(),
                current.schemaDigest(), current.schemaProfile(), null);
        assertEquals("CURRENT_MODEL_CHANGED", evaluate(f, f.report(), changed, Optional.empty(), OptionalLong.empty(), permits(f)).reason());
        var fields = reportFields(); evidence(fields).put("hardware", Map.of("model", "other-board", "boardRevision", 1L));
        assertEquals("HARDWARE_MISMATCH", evaluate(f, report(fields), current, Optional.empty(), OptionalLong.empty(), permits(f)).reason());
        fields = reportFields(); fields.put("bootloaderVersion", "2.0.0");
        assertEquals("BOOTLOADER_OUTSIDE_CONTROLLED_PROFILE", evaluate(f, report(fields), current, Optional.empty(), OptionalLong.empty(), permits(f)).reason());
    }
    /** 统一调用真实纯资格实现。 */
    private static OtaRollbackPreflightEvaluator.Decision evaluate(Fixture f, OtaRollbackPreflightReportCodec.Report report,
            OtaDeviceIdentity current, Optional<OtaDeviceCommitPort.SecurityFloor> floor, OptionalLong observed, List<UUID> permits) {
        return new OtaRollbackPreflightEvaluator().evaluate(f.source(), current, f.parent(), f.extension(), report, permits, floor, observed);
    }
    /** 原许可来自公开向量，保持独立身份匹配。 */
    private static List<UUID> permits(Fixture f) { return f.report().evidence().journal().commitOperations().stream().map(OtaRollbackPreflightReportCodec.CommitOperation::permitId).toList(); }
    /** 独立来源与父配置经真实codec解析。 */
    private static Fixture fixture() throws Exception {
        var parent = new OtaTypeBaselineCodec().decode(resource("baseline/type-baseline-v1.json"));
        var fields = new LinkedHashMap<>(new OtaCanonicalJson().parseObject(resource("device-report-v1.json")));
        fields.put("currentFirmwareSha256", "a".repeat(64));
        fields.put("thingModelVersionId", "018f0000-0000-7000-8000-000000000004");
        var source = new OtaDeviceReportCodec().decode(new OtaCanonicalJson().writeObject(fields)).value();
        var b = parent.value(); Map<String,Object> ext = new LinkedHashMap<>();
        ext.put("contractVersion", "tc-ota-rollback-baseline/v1"); ext.put("tenantId", b.tenantId().toString());
        ext.put("projectId", b.projectId().toString()); ext.put("deviceTypeId", b.deviceTypeId().toString());
        ext.put("productKey", b.productKey()); ext.put("typeBaselineVersion", b.baselineVersion());
        ext.put("typeBaselineSha256", parent.sha256()); ext.put("rollbackBaselineVersion", 1L);
        ext.put("atomicOperationProfile", "TC_OTA_AB_COMMIT_ROLLBACK_JOURNAL_V1");
        ext.put("bootloader", Map.of("minimumVersion", "1.2.0", "maximumVersion", "1.10.0"));
        ext.put("evidenceReference", "test-only:atomic-profile/1");
        var extension = new OtaRollbackBaselineCodec().decode(new OtaCanonicalJson().writeObject(ext)).value();
        var current = new OtaDeviceIdentity(b.tenantId(), b.projectId(), UUID.randomUUID(), b.deviceTypeId(), b.productKey(), 1,
                source.thingModelVersionId(), source.thingModelSchemaDigestAlgorithm(), source.thingModelSchemaDigest(), source.propertyProfile(), null);
        return new Fixture(source, current, b, extension, report(reportFields()));
    }
    /** 可变JSON仅在测试中递归复制。 */
    private static Object mutable(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new LinkedHashMap<>(); map.forEach((key,item) -> result.put((String)key, mutable(item))); return result;
        }
        if (value instanceof List<?> list) { var result = new ArrayList<>(); list.forEach(item -> result.add(mutable(item))); return result; }
        return value;
    }
    /** 公开报告的独立可变反例。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> reportFields() throws Exception {
        return (Map<String,Object>) mutable(new OtaCanonicalJson().parseObject(resource("rollback-preflight-report-v1.json")));
    }
    /** 真实严格词法恢复。 */
    private static OtaRollbackPreflightReportCodec.Report report(Map<String,Object> fields) { return new OtaRollbackPreflightReportCodec().decode(new OtaCanonicalJson().writeObject(fields)).value(); }
    /** 证据对象访问。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> evidence(Map<String,Object> fields) { return (Map<String,Object>) fields.get("evidence"); }
    /** 日志对象访问。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> journal(Map<String,Object> fields) { return (Map<String,Object>) evidence(fields).get("journal"); }
    /** 指定物理槽反例。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> slot(Map<String,Object> fields, String id) {
        return ((List<Map<String,Object>>) evidence(fields).get("slots")).stream().filter(slot -> id.equals(slot.get("slot"))).findFirst().orElseThrow();
    }
    /** 公开无私钥资源。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaRollbackPreflightEvaluatorTests.class.getResourceAsStream("/ota/" + name)) { return java.util.Objects.requireNonNull(input).readAllBytes(); }
    }
    /** 全部真实解析后的独立输入。 */
    private record Fixture(OtaDeviceReportCodec.Report source, OtaDeviceIdentity current, OtaTypeBaselineCodec.Baseline parent,
            OtaRollbackBaselineCodec.Baseline extension, OtaRollbackPreflightReportCodec.Report report) { }
}
