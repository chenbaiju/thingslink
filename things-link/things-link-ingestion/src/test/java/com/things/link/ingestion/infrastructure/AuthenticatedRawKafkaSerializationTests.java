package com.things.link.ingestion.infrastructure;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.RawUplinkMessage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证生产Kafka序列化器及反序列化器保留原代际，并兼容无身份的历史raw字节。 */
class AuthenticatedRawKafkaSerializationTests {
    /** 真实生产受信包配置，不使用任意类白名单。 */
    private static final Map<String, Object> CONFIG = Map.of(JsonDeserializer.TRUSTED_PACKAGES,
            "com.things.link.shared.message");

    /** long最大代际不经浮点化或当前值替换，完整身份跨类型头恢复。 */
    @Test
    void roundTripsOriginalGenerationWithProductionDecoder() {
        var identity = new AuthenticatedDeviceIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Long.MAX_VALUE);
        var raw = raw(identity);
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var restored = (RawUplinkMessage) decoder.deserialize("raw", headers,
                    serializer.serialize("raw", headers, raw));
            assertThat(restored.authenticatedIdentity()).isEqualTo(identity);
            assertThat(restored.payload()).isEqualTo(raw.payload());
        }
    }

    /** 历史正文缺认证对象时恢复null，不能默认拼出当前设备身份。 */
    @Test
    void legacyMissingIdentityRemainsAbsent() {
        var identity = new AuthenticatedDeviceIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L);
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(CONFIG, false);
            var headers = new RecordHeaders();
            var mapper = new ObjectMapper();
            var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
                    serializer.serialize("raw", headers, raw(identity)));
            node.remove("authenticatedIdentity");
            var restored = (RawUplinkMessage) decoder.deserialize("raw", headers, mapper.writeValueAsBytes(node));
            assertThat(restored.authenticatedIdentity()).isNull();
        }
    }

    /** 固定传输合同下建立连接设备raw夹具。 */
    private static RawUplinkMessage raw(AuthenticatedDeviceIdentity identity) {
        return new RawUplinkMessage(identity.tenantId(), identity.projectId(), identity.deviceId(),
                "tc/v1/project/device/up/property/report", new byte[] {1, 2, 3}, 1, false,
                "connection", Instant.parse("2026-09-12T00:00:00Z"), "trace", identity);
    }
}
