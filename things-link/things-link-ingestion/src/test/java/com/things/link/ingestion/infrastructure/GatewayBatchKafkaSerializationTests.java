package com.things.link.ingestion.infrastructure;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.SubDeviceReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证网关 batch Kafka 信封跨序列化边界后仍能恢复 sealed 条目的具体类型。 */
class GatewayBatchKafkaSerializationTests {

    /** Kafka 使用的增强 ObjectMapper；测试必须覆盖真实抽象字段反序列化，而非只调用 consumer 方法。 */
    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();

    /** 合法与拒绝条目都必须从既有无显式 discriminator 的 JSON 恢复，避免存量分区永久卡在首条。 */
    @Test
    void roundTripsValidAndRejectedEntriesByShape() throws Exception {
        GatewayBatchMessage source = new GatewayBatchMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 256,
                Instant.parse("2026-09-01T08:00:00Z"), "0123456789abcdef0123456789abcdef",
                List.of(
                        new GatewayBatchMessage.Valid(new SubDeviceReport(
                                Uuid7.generate(), "sub_01", Instant.parse("2026-09-01T07:59:59Z"),
                                "1.0.0", Map.of("temperature", 26.5)), 128),
                        new GatewayBatchMessage.Rejected(
                                GatewayBatchMessage.RejectReason.INVALID_MESSAGE_ID, 128)));

        String json = objectMapper.writeValueAsString(source);
        GatewayBatchMessage restored = objectMapper.readValue(json, GatewayBatchMessage.class);

        assertThat(restored.entries()).hasSize(2);
        assertThat(restored.entries().get(0)).isInstanceOf(GatewayBatchMessage.Valid.class);
        assertThat(restored.entries().get(1)).isInstanceOf(GatewayBatchMessage.Rejected.class);
        assertThat(((GatewayBatchMessage.Valid) restored.entries().get(0)).report().payload())
                .containsEntry("temperature", 26.5);
    }
}
