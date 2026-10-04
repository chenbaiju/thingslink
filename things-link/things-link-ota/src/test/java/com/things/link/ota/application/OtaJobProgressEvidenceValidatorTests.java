package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 原来源、目标与阶段证据的独立反例，不将匹配声明当作设备实际硬件证明。 */
class OtaJobProgressEvidenceValidatorTests {
    /** 本次刷写前启动身份。 */
    private static final UUID BOOT = UUID.fromString("018f0000-0000-7000-8000-000000000009");

    /** 合法阶段保持原boot，健康阶段必须切换boot和活动槽。 */
    @Test void legitimateStageSequencePreservesBootAndSwitchesSlotAtHealth() throws Exception {
        var fixture = fixture();
        for (String stage : List.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING")) {
            assertNull(check(fixture, progress(fixture, stage, Map.of()), "VERIFYING".equals(stage) ? null : BOOT));
        }
    }

    /** 目标摘要、长度、安全版本和目标模型任一不符都不能采用。 */
    @Test void rejectsDifferentSignedTargetTuple() throws Exception {
        var fixture = fixture();
        for (var change : List.of(Map.<String, Object>of("artifactSha256", "f".repeat(64)),
                Map.<String, Object>of("artifactSize", 1025L), Map.<String, Object>of("securityVersion", 2L),
                Map.<String, Object>of("thingModelVersionId", "00000000-0000-0000-0000-000000000001"),
                Map.<String, Object>of("thingModelSchemaDigest", "e".repeat(64)))) {
            assertEquals("MANIFEST_TUPLE_MISMATCH", check(fixture, progress(fixture, "VERIFYING", change), null));
        }
    }

    /** 信任声明不能替换原来源，也不能提前提高committed下限。 */
    @Test void rejectsChangedTrustAndPrematureCommit() throws Exception {
        var fixture = fixture();
        for (var change : List.of(Map.<String, Object>of("trustDomain", "other.domain"),
                Map.<String, Object>of("rootFingerprint", "f".repeat(64)), Map.<String, Object>of("trustBundleVersion", 2L),
                Map.<String, Object>of("trustBundleSha256", "f".repeat(64)))) {
            assertEquals("TRUST_TUPLE_MISMATCH", check(fixture, progress(fixture, "VERIFYING", change), null));
        }
        assertEquals("SECURITY_COMMIT_PREMATURE", check(fixture,
                progress(fixture, "VERIFYING", Map.of("committedSecurityVersion", 2L)), null));
    }

    /** 活动槽、声明检查和启动身份各自有独立安全边界。 */
    @Test void rejectsPrematureSlotSwitchMissingFlagsAndReusedHealthBoot() throws Exception {
        var fixture = fixture();
        assertEquals("SOURCE_SLOT_MISMATCH", check(fixture, progress(fixture, "INSTALLING", Map.of("activeSlot", "B")), BOOT));
        assertEquals("SOURCE_SLOT_MISMATCH", check(fixture, progress(fixture, "VERIFYING", Map.of("targetSlot", "A")), null));
        assertEquals("STAGE_EVIDENCE_INVALID", check(fixture, progress(fixture, "INSTALLING", Map.of("verification", "NOT_STARTED")), BOOT));
        assertEquals("STAGE_EVIDENCE_INVALID", check(fixture, progress(fixture, "HEALTH_CHECKING", Map.of("watchdogHealthy", false)), BOOT));
        var health = progress(fixture, "HEALTH_CHECKING", Map.of());
        assertEquals("BOOT_SESSION_MISMATCH", check(fixture, health, health.bootId()));
        assertEquals("BOOT_SESSION_MISMATCH", check(fixture, progress(fixture, "INSTALLING", Map.of()), UUID.randomUUID()));
        assertEquals("BOOT_SESSION_MISMATCH", check(fixture, progress(fixture, "REBOOTING", Map.of()), null));
    }

    /** 后报序号与余量更新不替换原槽/固件/硬件/模型/信任来源。 */
    @Test void sourceAllowsFreshSequenceAndResourcesButRejectsCriticalDrift() throws Exception {
        var fixture = fixture();
        var changed = new LinkedHashMap<>(fixture.sourceFields());
        changed.put("reportSequence", 2L);
        changed.put("availableRamBytes", 131072L);
        changed.put("availableFlashBytes", 2097152L);
        assertTrue(OtaJobProgressEvidenceValidator.sameSource(fixture.source(), report(changed)));
        for (var mutation : List.of(Map.<String, Object>of("activeSlot", "B"),
                Map.<String, Object>of("currentFirmwareSha256", "f".repeat(64)),
                Map.<String, Object>of("committedSecurityVersion", 0L),
                Map.<String, Object>of("bootloaderVersion", "1.4.0"),
                Map.<String, Object>of("trustBundleVersion", 2L),
                Map.<String, Object>of("thingModelSchemaDigest", "f".repeat(64)),
                Map.<String, Object>of("hardware", Map.of("model", "board-v1", "boardRevision", 2L)))) {
            var drifted = new LinkedHashMap<>(fixture.sourceFields());
            drifted.putAll(mutation);
            assertFalse(OtaJobProgressEvidenceValidator.sameSource(fixture.source(), report(drifted)));
        }
    }

    /** 持久清单损坏属于内部完整性失败，单槽声明属于安全策略不支持。 */
    @Test void corruptManifestIsNotDeviceProtocolErrorAndSingleSlotIsUnsupported() throws Exception {
        var fixture = fixture();
        var progress = progress(fixture, "VERIFYING", Map.of());
        assertThrows(IllegalStateException.class, () -> OtaJobProgressEvidenceValidator.mismatch(progress,
                fixture.source(), "{}".getBytes(StandardCharsets.UTF_8), null));
        var single = new LinkedHashMap<>(fixture.sourceFields());
        single.put("supportsAbSlots", false);
        single.put("activeSlot", "SINGLE");
        assertEquals("SAFE_SLOT_STRATEGY_UNSUPPORTED", OtaJobProgressEvidenceValidator.mismatch(progress,
                report(single), fixture.manifest(), null));
    }

    /** 使用真实词法codec，避免反例在不相关的字段类型处提前失败。 */
    private static OtaJobProgressCodec.Progress progress(Fixture f, String stage, Map<String, Object> overrides) {
        var m = new OtaCanonicalJson().parseObject(f.manifest());
        boolean health = "HEALTH_CHECKING".equals(stage);
        Map<String, Object> e = new LinkedHashMap<>();
        for (String name : List.of("artifactSha256", "artifactSize", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest")) e.put(name, m.get(name));
        e.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        e.put("committedSecurityVersion", f.source().committedSecurityVersion());
        e.put("trustDomain", f.source().trustDomain());
        e.put("rootFingerprint", f.source().rootFingerprint());
        e.put("trustBundleVersion", f.source().trustBundleVersion());
        e.put("trustBundleSha256", f.source().trustBundleSha256());
        e.put("sourceSlot", "A"); e.put("targetSlot", "B"); e.put("activeSlot", health ? "B" : "A");
        e.put("verification", "VERIFYING".equals(stage) ? "NOT_STARTED" : "PASSED");
        e.put("bootVerified", health); e.put("selfTestPassed", health); e.put("watchdogHealthy", health);
        e.putAll(overrides);
        var root = Map.<String, Object>of("contractVersion", "tc-ota-job-progress/v1", "jobId", UUID.randomUUID().toString(),
                "attemptNo", 1L, "progressSeq", 1L, "authorizationId", UUID.randomUUID().toString(),
                "manifestSha256", OtaTrustBundleCodec.sha256(f.manifest()), "stage", stage,
                "bootId", (health ? UUID.fromString("018f0000-0000-7000-8000-000000000010") : BOOT).toString(), "evidence", e);
        return new OtaJobProgressCodec().decode(new OtaCanonicalJson().writeObject(root)).value();
    }

    /** 统一比较入口。 */
    private static String check(Fixture f, OtaJobProgressCodec.Progress value, UUID boot) {
        return OtaJobProgressEvidenceValidator.mismatch(value, f.source(), f.manifest(), boot);
    }
    /** 真实报告词法恢复。 */
    private static OtaDeviceReportCodec.Report report(Map<String, Object> fields) {
        return new OtaDeviceReportCodec().decode(new OtaCanonicalJson().writeObject(fields)).value();
    }
    /** 以公开manifest/来源报告黄金资源构造一致目标，未生成或保存私钥。 */
    private static Fixture fixture() throws Exception {
        byte[] manifest = new OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        var source = new LinkedHashMap<>(new OtaCanonicalJson().parseObject(resource("device-report-v1.json")));
        source.put("trustDomain", "test.example");
        source.put("hardware", Map.of("model", "board-v1", "boardRevision", 1L));
        source.put("thingModelVersionId", "018f0000-0000-7000-8000-000000000004");
        return new Fixture(manifest, source, report(source));
    }
    /** 公开测试资源读取。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaJobProgressEvidenceValidatorTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("测试向量缺失");
            return input.readAllBytes();
        }
    }
    /** 本测试一致目标与原来源。
     * @param manifest 规范目标
     * @param sourceFields 原报告字段
     * @param source 类型化原来源
     */
    private record Fixture(byte[] manifest, Map<String, Object> sourceFields, OtaDeviceReportCodec.Report source) { }
}
