package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** 独立规范向量和失败边界，不模拟设备原子性证明。 */
class OtaRollbackExecutionCodecTests {
    /** 四个互不兼容的协议资源名称。 */
    private static final List<String> KINDS = List.of("operation", "operation-report", "status-query", "status-report");

    /** Python独立排序及摘要向量必须精确往返，输出规范字节为防御副本。 */
    @Test void independentGoldenVectorsRoundTrip() throws Exception {
        for (String kind : KINDS) {
            byte[] golden = resource(kind, ".json");
            assertArrayEquals(golden, roundTrip(kind, golden));
            assertEquals(new String(resource(kind, ".sha256"), StandardCharsets.UTF_8).trim(), hash(kind, golden));
        }
        var codec = new OtaRollbackOperationCodec();
        byte[] golden = resource("operation", ".json");
        var decoded = codec.decode(golden);
        golden[0] = 0;
        decoded.canonical()[0] = 0;
        assertArrayEquals(resource("operation", ".json"), decoded.canonical());
        assertThrows(UnsupportedOperationException.class, () -> decoded.value().permitIds().clear());
        var report = new OtaRollbackOperationReportCodec().decode(resource("operation-report", ".json"));
        report.canonical()[0] = 0;
        assertArrayEquals(resource("operation-report", ".json"), report.canonical());
        assertThrows(UnsupportedOperationException.class, () -> report.value().evidence().slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.value().evidence().journal().rollbackOperations().clear());
    }

    /** 原输入空白计入预算，四种协议均拒绝未知字段、重复键及彼此替换。 */
    @Test void allEnvelopesAreClosedAndBounded() throws Exception {
        for (String kind : KINDS) {
            byte[] golden = resource(kind, ".json");
            byte[] exact = Arrays.copyOf(golden, 16384);
            Arrays.fill(exact, golden.length, exact.length, (byte) ' ');
            assertDoesNotThrow(() -> roundTrip(kind, exact));
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, Arrays.copyOf(exact, 16385)));
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, null));
            var value = fields(kind);
            value.put("deviceId", "untrusted");
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, bytes(value)));
            String duplicate = new String(golden, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"attemptNo\":1,");
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, duplicate.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(IllegalArgumentException.class, () -> roundTrip("status-report", resource("operation-report", ".json")));
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", resource("status-report", ".json")));
    }

    /** CAS接纳必须为加一留出空间，期限和报告序号不允许宽松转换。 */
    @Test void revisionsAndTimestampsRespectExactBounds() throws Exception {
        var operation = fields("operation");
        operation.put("expectedOperationRevision", 9007199254740990L);
        operation.put("expiresAt", 253402300799L);
        assertDoesNotThrow(() -> roundTrip("operation", bytes(operation)));
        operation.put("expectedOperationRevision", 9007199254740991L);
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation", bytes(operation)));
        for (String kind : KINDS) {
            var value = fields(kind);
            value.put("attemptNo", 2147483648L);
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, bytes(value)));
            var identity = fields(kind);
            identity.put("authorizationId", "not-uuid");
            byte[] invalidUuid = bytes(identity);
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, invalidUuid));
        }
        for (String kind : List.of("operation-report", "status-report")) {
            for (Object invalid : List.of(0L, "1", 9007199254740992L)) {
                var value = fields(kind);
                value.put("reportSeq", invalid);
                assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, bytes(value)));
            }
        }
        var query = fields("status-query");
        query.put("expiresAt", 253402300800L);
        assertThrows(IllegalArgumentException.class, () -> roundTrip("status-query", bytes(query)));
    }

    /** 无接纳记录是合法未知观察，空数组不被共享非空集合工具错误拒绝。 */
    @Test void emptyJournalsAndUnknownRefusedRemainObservable() throws Exception {
        var operation = fields("operation");
        operation.put("permitIds", List.of());
        assertDoesNotThrow(() -> roundTrip("operation", bytes(operation)));
        for (String kind : List.of("operation-report", "status-report")) {
            var value = fields(kind);
            var evidence = mutable(value.get("evidence"));
            var journal = mutable(evidence.get("journal"));
            value.put("evidence", evidence);
            evidence.put("journal", journal);
            journal.put("commitOperations", List.of());
            journal.put("rollbackOperations", List.of());
            for (String state : List.of("REFUSED", "UNKNOWN", "COMMIT_WON")) {
                value.put("status", state);
                assertDoesNotThrow(() -> roundTrip(kind, bytes(value)));
            }
            journal.put("rollbackOperations", List.of(Map.of(), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> roundTrip(kind, bytes(value)));
        }
    }

    /** 接纳boot与当前boot分别保存，词法层不代业务判断修订及方向矛盾。 */
    @Test void durableAcceptanceIdentityIsRequiredAndNotInferred() throws Exception {
        var value = fields("operation-report");
        var evidence = mutable(value.get("evidence"));
        var journal = mutable(evidence.get("journal"));
        var operation = mutable(((List<?>) journal.get("rollbackOperations")).getFirst());
        value.put("evidence", evidence);
        evidence.put("journal", journal);
        journal.put("rollbackOperations", List.of(operation));
        operation.put("acceptedRevision", 9007199254740991L);
        assertDoesNotThrow(() -> roundTrip("operation-report", bytes(value)));
        operation.put("acceptedRevision", 0L);
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", bytes(value)));
        operation.put("acceptedRevision", 8L);
        operation.remove("acceptedBootId");
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", bytes(value)));
        journal.put("rollbackOperations", List.of());
        journal.put("state", "IDLE");
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", bytes(value)));
    }

    /** 槽数组唯一且规范排序，七字段来源不能夹带未经授权的目标配置。 */
    @Test void slotsAndSourceHaveIndependentClosedShapes() throws Exception {
        var value = fields("operation-report");
        var evidence = mutable(value.get("evidence"));
        var slots = (List<?>) evidence.get("slots");
        value.put("evidence", evidence);
        evidence.put("slots", List.of(slots.get(1), slots.get(0)));
        assertArrayEquals(resource("operation-report", ".json"), roundTrip("operation-report", bytes(value)));
        evidence.put("slots", List.of(slots.get(0), slots.get(0)));
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", bytes(value)));
        evidence.put("slots", List.of(slots.get(0)));
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation-report", bytes(value)));
        var operation = fields("operation");
        var source = mutable(operation.get("source"));
        operation.put("source", source);
        source.put("health", "HEALTHY");
        assertThrows(IllegalArgumentException.class, () -> roundTrip("operation", bytes(operation)));
    }

    /** 无对象编码必须固定拒绝。 */
    @Test void nullEncodingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackOperationCodec().encode(null));
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackOperationReportCodec().encode(null));
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackStatusQueryCodec().encode(null));
        assertThrows(IllegalArgumentException.class, () -> new OtaRollbackStatusReportCodec().encode(null));
    }

    /** 四个公开入口完整解码并重新编码。 */
    private static byte[] roundTrip(String kind, byte[] input) {
        return switch (kind) {
            case "operation" -> { var codec = new OtaRollbackOperationCodec(); yield codec.encode(codec.decode(input).value()); }
            case "operation-report" -> { var codec = new OtaRollbackOperationReportCodec(); yield codec.encode(codec.decode(input).value()); }
            case "status-query" -> { var codec = new OtaRollbackStatusQueryCodec(); yield codec.encode(codec.decode(input).value()); }
            case "status-report" -> { var codec = new OtaRollbackStatusReportCodec(); yield codec.encode(codec.decode(input).value()); }
            default -> throw new IllegalArgumentException("测试协议不存在");
        };
    }
    /** 比较公开解码结果摘要。 */
    private static String hash(String kind, byte[] input) {
        return switch (kind) {
            case "operation" -> new OtaRollbackOperationCodec().decode(input).sha256();
            case "operation-report" -> new OtaRollbackOperationReportCodec().decode(input).sha256();
            case "status-query" -> new OtaRollbackStatusQueryCodec().decode(input).sha256();
            case "status-report" -> new OtaRollbackStatusReportCodec().decode(input).sha256();
            default -> throw new IllegalArgumentException("测试协议不存在");
        };
    }
    /** 复制规范向量以构造独立反例。 */
    private static Map<String, Object> fields(String kind) throws Exception {
        return mutable(new OtaCanonicalJson().parseObject(resource(kind, ".json")));
    }
    /** 嵌套映射复制，不修改原规范值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Object value) { return new LinkedHashMap<>((Map<String, Object>) value); }
    /** 反例保留真实JSON词法路径。 */
    private static byte[] bytes(Map<String, Object> value) { return new OtaCanonicalJson().writeObject(value); }
    /** 固定公开向量，无密码、私钥或签址。 */
    private static byte[] resource(String kind, String suffix) throws Exception {
        try (var input = OtaRollbackExecutionCodecTests.class.getResourceAsStream("/ota/rollback-" + kind + "-v1" + suffix)) {
            return Objects.requireNonNull(input).readAllBytes();
        }
    }
}
