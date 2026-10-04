package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 对账独立协议的固定向量与严格边界，未引入私钥或设备身份推断。 */
class OtaCommitReconciliationCodecTests {
    /** Python独立规范向量及摘要必须精确往返。 */
    @Test void canonicalGoldenRoundTrips() throws Exception {
        byte[] query = resource("query", ".json");
        var q = new OtaCommitReconciliationQueryCodec().decode(query);
        assertArrayEquals(query, q.canonical());
        assertArrayEquals(query, new OtaCommitReconciliationQueryCodec().encode(q.value()));
        assertEquals(hash("query"), q.sha256());
        byte[] report = resource("report", ".json");
        var r = new OtaCommitReconciliationReportCodec().decode(report);
        assertArrayEquals(report, r.canonical());
        assertArrayEquals(report, new OtaCommitReconciliationReportCodec().encode(r.value()));
        assertEquals(hash("report"), r.sha256());
        assertNotEquals(r.value().bootId(), r.value().commitBootId());
        q.canonical()[0] = 0; r.canonical()[0] = 0;
        assertArrayEquals(query, q.canonical()); assertArrayEquals(report, r.canonical());
        query[0] = 0; report[0] = 0;
        assertEquals('{', q.canonical()[0]); assertEquals('{', r.canonical()[0]);
    }
    /** 原输入空白同样计入16KiB预算。 */
    @Test void boundedRawBytes() throws Exception {
        for (String kind : new String[]{"query", "report"}) {
            byte[] original = resource(kind, ".json");
            byte[] exact = Arrays.copyOf(original, 16384);
            Arrays.fill(exact, original.length, exact.length, (byte) ' ');
            assertDoesNotThrow(() -> decode(kind, exact));
            assertThrows(IllegalArgumentException.class, () -> decode(kind, Arrays.copyOf(exact, 16385)));
            assertThrows(IllegalArgumentException.class, () -> decode(kind, null));
            assertThrows(IllegalArgumentException.class, () -> decode(kind, new byte[0]));
        }
    }
    /** 重复字段、未知字段与协议互换均拒绝。 */
    @Test void closedContractsAndDuplicates() throws Exception {
        for (String kind : new String[]{"query", "report"}) {
            String original = new String(resource(kind, ".json"), StandardCharsets.UTF_8);
            byte[] duplicate = original.replaceFirst("\\{", "{\"attemptNo\":1,").getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> decode(kind, duplicate));
            var value = fields(kind); value.put("deviceId", "untrusted");
            assertThrows(IllegalArgumentException.class, () -> decode(kind, bytes(value)));
        }
        assertThrows(IllegalArgumentException.class, () -> decode("query", resource("report", ".json")));
        assertThrows(IllegalArgumentException.class, () -> decode("report", resource("query", ".json")));
    }
    /** 恢复修订与nonce不可缺失或宽松转换。 */
    @Test void exactRecoveryIdentityAndNumbers() throws Exception {
        for (String kind : new String[]{"query", "report"}) {
            for (var mutation : java.util.List.of(Map.<String,Object>of("recoveryRevision", 0L),
                    Map.<String,Object>of("recoveryRevision", "9"), Map.<String,Object>of("attemptNo", 2147483648L),
                    Map.<String,Object>of("queryNonce", "invalid"), Map.<String,Object>of("manifestSha256", "A".repeat(64)))) {
                var value = fields(kind); value.putAll(mutation);
                assertThrows(IllegalArgumentException.class, () -> decode(kind, bytes(value)));
            }
        }
    }
    /** 对账证据仍是十九字段，原boot与当前boot分别保存；安全矛盾由服务判断。 */
    @Test void evidenceClosedButSemanticMismatchPreserved() throws Exception {
        var value = fields("report");
        @SuppressWarnings("unchecked") var evidence = new LinkedHashMap<>((Map<String,Object>) value.get("evidence"));
        value.put("evidence", evidence); evidence.put("committedSecurityVersion", 0L);
        assertEquals(0, new OtaCommitReconciliationReportCodec().decode(bytes(value)).value().evidence().committedSecurityVersion());
        evidence.put("uptimeMillis", 1L);
        assertThrows(IllegalArgumentException.class, () -> decode("report", bytes(value)));
    }
    /** 查询期限固定正整数秒且不接受无对象编码。 */
    @Test void deadlineAndNullEncodeRejected() throws Exception {
        var value = fields("query"); value.put("expiresAt", 253402300800L);
        assertThrows(IllegalArgumentException.class, () -> decode("query", bytes(value)));
        assertThrows(IllegalArgumentException.class, () -> new OtaCommitReconciliationQueryCodec().encode(null));
        assertThrows(IllegalArgumentException.class, () -> new OtaCommitReconciliationReportCodec().encode(null));
    }
    /** 各独立解码入口。 */
    private static void decode(String kind, byte[] bytes) {
        if ("query".equals(kind)) new OtaCommitReconciliationQueryCodec().decode(bytes);
        else new OtaCommitReconciliationReportCodec().decode(bytes);
    }
    /** 原向量字段复制供反例修改。 */
    private static Map<String,Object> fields(String kind) throws Exception {
        return new LinkedHashMap<>(new OtaCanonicalJson().parseObject(resource(kind, ".json")));
    }
    /** 反例规范化。 */
    private static byte[] bytes(Map<String,Object> fields) { return new OtaCanonicalJson().writeObject(fields); }
    /** 独立摘要读取。 */
    private static String hash(String kind) throws Exception {
        return new String(resource(kind, ".sha256"), StandardCharsets.UTF_8).trim();
    }
    /** 固定公开向量，无凭据与私钥。 */
    private static byte[] resource(String kind, String suffix) throws Exception {
        try (var input = OtaCommitReconciliationCodecTests.class.getResourceAsStream("/ota/commit-reconciliation-" + kind + "-v1" + suffix)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }
}
