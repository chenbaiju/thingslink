package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 独立固定向量验证新合同，不扩展旧进度协议。 */
class OtaConfirmationCodecTests {
    /** 三种独立Python规范向量与固定摘要一致。 */
    @Test void goldenVectorsAndDefensiveCopies() throws Exception {
        byte[] health = resource("health-v1.json");
        var h = new OtaHealthCodec().decode(health);
        assertArrayEquals(health, h.canonical());
        assertEquals(hash("health-v1"), h.sha256());
        assertEquals(9007199254740991L, h.value().evidence().uptimeMillis());
        h.canonical()[0] = 0;
        assertArrayEquals(health, h.canonical());
        byte[] receipt = resource("commit-receipt-v1.json");
        var r = new OtaCommitReceiptCodec().decode(receipt);
        assertArrayEquals(receipt, r.canonical());
        assertEquals(hash("commit-receipt-v1"), r.sha256());
        byte[] permit = resource("commit-permit-v1.json");
        var p = new OtaCommitPermitCodec().decode(permit);
        assertArrayEquals(permit, p.canonical());
        assertEquals(hash("commit-permit-v1"), p.sha256());
        assertArrayEquals(permit, new OtaCommitPermitCodec().encode(p.value()));
        receipt[0] = 0; permit[0] = 0;
        assertEquals('{', r.canonical()[0]);
        assertEquals('{', p.canonical()[0]);
    }
    /** 原输入严格16KiB，空白计入预算。 */
    @Test void rawByteBudgetIncludesWhitespace() throws Exception {
        for (String name : new String[]{"health-v1", "commit-receipt-v1", "commit-permit-v1"}) {
            byte[] original = resource(name + ".json");
            byte[] full = Arrays.copyOf(original, 16384);
            Arrays.fill(full, original.length, full.length, (byte) ' ');
            assertDoesNotThrow(() -> decode(name, full));
            byte[] over = Arrays.copyOf(full, 16385); over[16384] = ' ';
            assertThrows(IllegalArgumentException.class, () -> decode(name, over));
            assertThrows(IllegalArgumentException.class, () -> decode(name, null));
        }
    }
    /** 新协议必须闭集且拒绝重复键和跨协议载荷。 */
    @Test void strictEnvelopeAndVersionIsolation() throws Exception {
        for (String name : new String[]{"health-v1", "commit-receipt-v1", "commit-permit-v1"}) {
            String original = new String(resource(name + ".json"), StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> decode(name,
                    original.replaceFirst("\\{", "{\"attemptNo\":1,").getBytes(StandardCharsets.UTF_8)));
            var value = fields(name); value.put("unknown", true);
            assertThrows(IllegalArgumentException.class, () -> decode(name, bytes(value)));
        }
        assertThrows(IllegalArgumentException.class, () -> new OtaJobProgressCodec().decode(resource("health-v1.json")));
        assertThrows(IllegalArgumentException.class, () -> new OtaHealthCodec().decode(resource("job-progress-v1.json")));
    }
    /** 持续健康不能超过本次启动，类型与安全整数边界独立核验。 */
    @Test void boundedHealthClocksAndClosedEvidence() throws Exception {
        var value = fields("health-v1");
        @SuppressWarnings("unchecked") var evidence = new LinkedHashMap<>((Map<String,Object>) value.get("evidence"));
        value.put("evidence", evidence);
        evidence.put("uptimeMillis", 1L); evidence.put("healthyForMillis", 2L);
        assertThrows(IllegalArgumentException.class, () -> new OtaHealthCodec().decode(bytes(value)));
        evidence.put("healthyForMillis", "1");
        assertThrows(IllegalArgumentException.class, () -> new OtaHealthCodec().decode(bytes(value)));
        evidence.put("healthyForMillis", 0L); evidence.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> new OtaHealthCodec().decode(bytes(value)));
    }
    /** 安全矛盾仍进入领域裁决，codec不伪造健康证明。 */
    @Test void semanticMismatchRemainsParseable() throws Exception {
        var value = fields("commit-receipt-v1");
        @SuppressWarnings("unchecked") var evidence = new LinkedHashMap<>((Map<String,Object>) value.get("evidence"));
        evidence.put("verification", "NOT_STARTED"); evidence.put("bootVerified", false);
        value.put("evidence", evidence);
        assertFalse(new OtaCommitReceiptCodec().decode(bytes(value)).value().evidence().bootVerified());
    }
    /** 许可期限为整数秒，槽与身份不可弱化。 */
    @Test void permitRejectsInvalidDeadlineAndSlot() throws Exception {
        for (var change : java.util.List.of(Map.<String,Object>of("expiresAt", 0L),
                Map.<String,Object>of("expiresAt", "1789257600"), Map.<String,Object>of("targetSlot", "SINGLE"),
                Map.<String,Object>of("permitId", "not-uuid"))) {
            var value = fields("commit-permit-v1"); value.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> new OtaCommitPermitCodec().decode(bytes(value)));
        }
    }
    /** 各协议独立解析入口。 */
    private static void decode(String name, byte[] value) {
        switch (name) {
            case "health-v1" -> new OtaHealthCodec().decode(value);
            case "commit-receipt-v1" -> new OtaCommitReceiptCodec().decode(value);
            default -> new OtaCommitPermitCodec().decode(value);
        }
    }
    /** 可变反例顶层字段。 */
    private static Map<String,Object> fields(String name) throws Exception {
        return new LinkedHashMap<>(new OtaCanonicalJson().parseObject(resource(name + ".json")));
    }
    /** 测试结构编码。 */
    private static byte[] bytes(Map<String,Object> value) { return new OtaCanonicalJson().writeObject(value); }
    /** 独立固定黄金摘要。 */
    private static String hash(String name) throws Exception {
        return new String(resource(name + ".sha256"), StandardCharsets.UTF_8).trim();
    }
    /** 公开无密钥资源。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaConfirmationCodecTests.class.getResourceAsStream("/ota/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }
}
