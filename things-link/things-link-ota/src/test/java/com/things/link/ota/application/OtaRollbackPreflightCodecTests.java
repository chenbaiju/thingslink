package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 对账独立协议的固定向量与严格边界，未引入私钥或设备身份推断。 */
class OtaRollbackPreflightCodecTests {
    /** Python独立规范向量及摘要必须精确往返。 */
    @Test void canonicalGoldenRoundTrips() throws Exception {
        byte[] query = resource("query", ".json");
        var q = new OtaRollbackPreflightQueryCodec().decode(query);
        assertArrayEquals(query, q.canonical());
        assertArrayEquals(query, new OtaRollbackPreflightQueryCodec().encode(q.value()));
        assertEquals(hash("query"), q.sha256());
        byte[] report = resource("report", ".json");
        var r = new OtaRollbackPreflightReportCodec().decode(report);
        assertArrayEquals(report, r.canonical());
        assertArrayEquals(report, new OtaRollbackPreflightReportCodec().encode(r.value()));
        assertEquals(hash("report"), r.sha256());
        assertEquals("A", r.value().evidence().slots().getFirst().slot());
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
    /** 双槽数组按槽规范化，重复槽或缺槽都不能伪装完整预检。 */
    @Test void slotsAreCompleteUniqueAndCanonical() throws Exception {
        var value = fields("report");
        @SuppressWarnings("unchecked") var evidence = new LinkedHashMap<>((Map<String,Object>) value.get("evidence"));
        @SuppressWarnings("unchecked") var slots = (java.util.List<Object>) evidence.get("slots");
        value.put("evidence", evidence);
        evidence.put("slots", java.util.List.of(slots.get(1), slots.get(0)));
        assertArrayEquals(resource("report", ".json"), new OtaRollbackPreflightReportCodec().decode(bytes(value)).canonical());
        evidence.put("slots", java.util.List.of(slots.get(0), slots.get(0)));
        assertThrows(IllegalArgumentException.class, () -> decode("report", bytes(value)));
        evidence.put("slots", java.util.List.of(slots.get(0)));
        assertThrows(IllegalArgumentException.class, () -> decode("report", bytes(value)));
    }
    /** 已提交或未知日志是合法观察，交给资格拒绝而不在词法层丢证据。 */
    @Test void unsafeJournalRemainsObservableAndFieldsStayClosed() throws Exception {
        var value = fields("report");
        @SuppressWarnings("unchecked") var evidence = new LinkedHashMap<>((Map<String,Object>) value.get("evidence"));
        @SuppressWarnings("unchecked") var journal = new LinkedHashMap<>((Map<String,Object>) evidence.get("journal"));
        value.put("evidence", evidence); evidence.put("journal", journal);
        journal.put("state", "COMMITTED"); value.put("committedSecurityVersion", 2L);
        assertEquals("COMMITTED", new OtaRollbackPreflightReportCodec().decode(bytes(value)).value().evidence().journal().state());
        journal.put("permitIds", java.util.List.of());
        assertThrows(IllegalArgumentException.class, () -> decode("report", bytes(value)));
    }
    /** 查询期限固定正整数秒且不接受无对象编码。 */
    @Test void deadlineAndNullEncodeRejected() throws Exception {
        var value = fields("query"); value.put("expiresAt", 253402300800L);
        assertThrows(IllegalArgumentException.class, () -> decode("query", bytes(value)));
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackPreflightQueryCodec().encode(null));
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackPreflightReportCodec().encode(null));
    }
    /** 从未签发许可的预检允许空操作数组，仍保持类型和数量限制。 */
    @Test void emptyOperationArraysAreValidButMalformedArraysAreRejected() throws Exception {
        var query = fields("query");
        query.put("permitIds", java.util.List.of());
        var queryCodec = new OtaRollbackPreflightQueryCodec();
        var decodedQuery = queryCodec.decode(bytes(query));
        assertEquals(java.util.List.of(), decodedQuery.value().permitIds());
        assertArrayEquals(bytes(query), queryCodec.encode(decodedQuery.value()));

        var report = fields("report");
        @SuppressWarnings("unchecked")
        var evidence = new LinkedHashMap<>((Map<String, Object>) report.get("evidence"));
        @SuppressWarnings("unchecked")
        var journal = new LinkedHashMap<>((Map<String, Object>) evidence.get("journal"));
        report.put("evidence", evidence);
        evidence.put("journal", journal);
        journal.put("commitOperations", java.util.List.of());
        journal.put("rollbackOperationIds", java.util.List.of());
        var reportCodec = new OtaRollbackPreflightReportCodec();
        var decodedReport = reportCodec.decode(bytes(report));
        assertEquals(java.util.List.of(), decodedReport.value().evidence().journal().commitOperations());
        assertEquals(java.util.List.of(), decodedReport.value().evidence().journal().rollbackOperationIds());
        assertArrayEquals(bytes(report), reportCodec.encode(decodedReport.value()));

        for (Object malformed : java.util.List.of("not-an-array", java.util.List.of("a", "b"))) {
            query.put("permitIds", malformed);
            assertThrows(IllegalArgumentException.class, () -> queryCodec.decode(bytes(query)));
            for (String field : java.util.List.of("commitOperations", "rollbackOperationIds")) {
                journal.put(field, malformed);
                assertThrows(IllegalArgumentException.class, () -> reportCodec.decode(bytes(report)));
                journal.put(field, java.util.List.of());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> OtaRollbackPreflightJson.ids(null));
        journal.put("commitOperations", null);
        assertThrows(IllegalArgumentException.class, () -> OtaRollbackPreflightJson.evidence(evidence));
    }
    /** 各独立解码入口。 */
    private static void decode(String kind, byte[] bytes) {
        if ("query".equals(kind)) new OtaRollbackPreflightQueryCodec().decode(bytes);
        else new OtaRollbackPreflightReportCodec().decode(bytes);
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
        try (var input = OtaRollbackPreflightCodecTests.class.getResourceAsStream("/ota/rollback-preflight-" + kind + "-v1" + suffix)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }
}
