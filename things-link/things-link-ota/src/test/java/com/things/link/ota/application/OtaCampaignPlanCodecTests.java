package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 活动计划独立黄金向量、排序分批及策略边界。 */
class OtaCampaignPlanCodecTests {
    /** 真实有界JSON入口。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 被测计划合同。 */
    private final OtaCampaignPlanCodec codec = new OtaCampaignPlanCodec();

    /** UUID按字符串而非有符号位排序，末批及不可变身份保持稳定。 */
    @Test void goldenCanonicalOrderingAndImmutableBatches() throws Exception {
        byte[] golden = resource("campaign-plan-v1.json");
        var decoded = codec.decode(golden);
        assertArrayEquals(golden, decoded.canonical());
        assertEquals(new String(resource("campaign-plan-v1.sha256"), StandardCharsets.US_ASCII).trim(), decoded.sha256());
        var fields = fields();
        var reversed = new ArrayList<>((List<?>) fields.get("deviceIds"));
        Collections.reverse(reversed);
        fields.put("deviceIds", reversed);
        assertEquals(decoded.sha256(), decode(fields).sha256());
        var batches = decoded.value().batches();
        assertEquals(List.of(2, 1), batches.stream().map(List::size).toList());
        assertEquals(UUID.fromString("80000000-0000-0000-0000-000000000000"), batches.getLast().getFirst());
        assertThrows(UnsupportedOperationException.class, batches::clear);
        assertThrows(UnsupportedOperationException.class, () -> batches.getFirst().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.value().deviceIds().clear());
        byte[] copy = decoded.canonical();
        copy[0] = 0;
        assertArrayEquals(golden, decoded.canonical());
    }

    /** 目标数量、重复、非规范UUID与最大合法批次。 */
    @Test void targetBoundariesAndDuplicateRejection() throws Exception {
        var fields = fields();
        var ids = new ArrayList<String>();
        for (int i = 1; i <= 1000; i++) ids.add(new UUID(0, i).toString());
        fields.put("deviceIds", ids);
        fields.put("batchSize", 1000L);
        assertEquals(1000, decode(fields).value().batches().getFirst().size());
        ids.add(new UUID(0, 1001).toString());
        assertThrows(IllegalArgumentException.class, () -> decode(fields));
        for (Object invalid : List.of(List.of(), List.of(ids.getFirst(), ids.getFirst()), List.of("0-0-0-0-1"))) {
            fields.put("deviceIds", invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(fields));
        }
    }

    /** 每种预算上下界及真实布尔不能被默认值替代。 */
    @Test void rejectsPolicyOutsideFrozenBounds() throws Exception {
        Map<String, Object> invalids = Map.ofEntries(Map.entry("maxConcurrentDownloads", 3L),
                Map.entry("maxDownloadBytesPerSecond", 1_073_741_825L), Map.entry("downloadRetryLimit", 11L),
                Map.entry("retryBackoffSeconds", 3601L), Map.entry("healthWindowSeconds", 121L),
                Map.entry("pauseMinEvaluated", 3L), Map.entry("pauseFailureCount", 0L),
                Map.entry("pauseFailureRateBps", 0L), Map.entry("batchMinSuccessRateBps", 10001L),
                Map.entry("requireManualBatchApproval", "true"));
        for (var invalid : invalids.entrySet()) {
            var fields = fields();
            var policy = new LinkedHashMap<>(OtaTrustBundleCodec.object(fields.get("executionPolicy")));
            policy.put(invalid.getKey(), invalid.getValue());
            fields.put("executionPolicy", policy);
            assertThrows(IllegalArgumentException.class, () -> decode(fields), invalid.getKey());
        }
    }

    /** 状态闭集与健康窗口不能因阶段漏项或零预算弱化。 */
    @Test void stageTimeoutsAreClosedAndBounded() throws Exception {
        for (String action : List.of("missing", "unknown", "zero", "large")) {
            var fields = fields();
            var policy = new LinkedHashMap<>(OtaTrustBundleCodec.object(fields.get("executionPolicy")));
            var stages = new LinkedHashMap<>(OtaTrustBundleCodec.object(policy.get("stageTimeoutSeconds")));
            switch (action) {
                case "missing" -> stages.remove("VERIFYING");
                case "unknown" -> stages.put("RECOVERY_REQUIRED", 120L);
                case "zero" -> stages.put("INSTALLING", 0L);
                default -> stages.put("CONFIRMING", 86401L);
            }
            policy.put("stageTimeoutSeconds", stages);
            fields.put("executionPolicy", policy);
            assertThrows(IllegalArgumentException.class, () -> decode(fields), action);
        }
    }

    /** UTC秒精度及真实日期，不接受闰秒归一化或扩展年。 */
    @Test void rejectsNonCanonicalOrImpossibleDates() throws Exception {
        for (String date : List.of("2026-09-12T00:00:00.000Z", "2026-09-12T00:00:00+00:00",
                "2026-02-30T00:00:00Z", "2026-09-12T24:00:00Z", "2016-12-31T23:59:60Z",
                "+10000-01-01T00:00:00Z")) {
            var fields = fields();
            fields.put("notBefore", date);
            assertThrows(IllegalArgumentException.class, () -> decode(fields), date);
        }
    }

    /** 原始入口拒绝身份透传、重复字段、null、坏编码与超预算。 */
    @Test void rejectsInvalidRawShape() throws Exception {
        var fields = fields();
        fields.put("tenantId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> decode(fields));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"deviceIds\":null}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"a\":1,\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[65537]));
    }

    /** 所有变体经过生产规范器和codec。 */
    private OtaCampaignPlanCodec.Decoded decode(Map<String, Object> fields) {
        return codec.decode(json.writeObject(fields));
    }
    /** 独立公开黄金向量副本。 */
    private Map<String, Object> fields() throws Exception {
        return new LinkedHashMap<>(json.parseObject(resource("campaign-plan-v1.json")));
    }
    /** 读取公开固定资源，无任何生产秘密。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaCampaignPlanCodecTests.class.getResourceAsStream("/ota/" + name)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
