package com.things.link.ingestion.application;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.RawUplinkMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证网关批量属性上报的帧级严格校验、逐条目 messageId 容错与字节确定性均分。 */
class GatewayBatchMessageNormalizerTests {

    /** 默认Boot映射器作为输入，局部精度配置由被测标准化器负责。 */
    private final GatewayBatchMessageNormalizer normalizer =
            new GatewayBatchMessageNormalizer(new ObjectMapper(), new SimpleMeterRegistry());

    /** 合法批量帧应保留可信归属、按原始顺序均分字节，且各条目字节合计等于帧原始字节。 */
    @Test
    void normalizesBatchAndDistributesBytesEvenly() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID gatewayId = Uuid7.generate();
        UUID firstId = Uuid7.generate();
        UUID secondId = Uuid7.generate();
        byte[] payload = ("{\"devices\":["
                + "{\"messageId\":\"" + firstId + "\",\"deviceKey\":\"sub_01\",\"occurredAt\":\"2026-08-14T08:00:00Z\",\"payload\":{\"t\":23.5}},"
                + "{\"messageId\":\"" + secondId + "\",\"deviceKey\":\"sub_02\",\"occurredAt\":\"2026-08-14T08:00:01Z\",\"payload\":{\"t\":24.0}}"
                + "]}").getBytes(StandardCharsets.UTF_8);
        RawUplinkMessage raw = rawMessage(tenantId, projectId, gatewayId, payload);

        GatewayBatchMessage message = normalizer.tryNormalize(raw).orElseThrow();

        assertThat(message.tenantId()).isEqualTo(tenantId);
        assertThat(message.projectId()).isEqualTo(projectId);
        assertThat(message.gatewayId()).isEqualTo(gatewayId);
        assertThat(message.frameBytes()).isEqualTo(payload.length);
        assertThat(message.entries()).hasSize(2);

        int bytesSum = message.entries().stream().mapToInt(entry -> switch (entry) {
            case GatewayBatchMessage.Valid valid -> valid.rawBytes();
            case GatewayBatchMessage.Rejected rejected -> rejected.rawBytes();
        }).sum();
        assertThat(bytesSum).isEqualTo(payload.length);

        GatewayBatchMessage.Valid first = (GatewayBatchMessage.Valid) message.entries().get(0);
        assertThat(first.report().messageId()).isEqualTo(firstId);
        assertThat(first.report().deviceKey()).isEqualTo("sub_01");
        assertThat(first.report().payload()).containsEntry("t", new BigDecimal("23.5"));
    }

    /** 原始长小数不能先经过Double树节点；复合属性中的整数和列表也须保持数值。 */
    @Test
    void preservesNestedDecimalAndLargeIntegerFromOriginalBatchBytes() {
        String decimal = "9007199254740993.123456789";
        String integer = "9007199254740993123456789";
        byte[] bytes = ("{\"devices\":[{\"messageId\":\"" + Uuid7.generate()
                + "\",\"deviceKey\":\"sub_01\",\"occurredAt\":\"2026-08-14T08:00:00Z\","
                + "\"modelVersion\":\"1.0.0\",\"payload\":{\"object\":{\"decimal\":" + decimal
                + ",\"integer\":" + integer + ",\"list\":[" + decimal + "," + integer + "]}}}]}")
                .getBytes(StandardCharsets.UTF_8);
        var suppliedMapper = new ObjectMapper();
        var subject = new GatewayBatchMessageNormalizer(suppliedMapper, new SimpleMeterRegistry());
        var message = subject.tryNormalize(rawMessage(bytes)).orElseThrow();
        var payload = ((GatewayBatchMessage.Valid) message.entries().getFirst()).report().payload();
        Map<?, ?> object = (Map<?, ?>) payload.get("object");
        assertThat(object.get("decimal")).isEqualTo(new BigDecimal(decimal));
        assertThat(object.get("integer")).isEqualTo(new BigInteger(integer));
        assertThat((List<?>) object.get("list")).isEqualTo(List.of(new BigDecimal(decimal), new BigInteger(integer)));
        assertThat(message.frameBytes()).isEqualTo(bytes.length);
        // 调用方映射器仍保持默认Double，窄修复不污染其他HTTP或消息类型。
        assertThat(suppliedMapper.readValue("{\"decimal\":" + decimal + "}", Map.class).get("decimal"))
                .isInstanceOf(Double.class);
    }

    /** 缺失或非法 messageId 按条目拒绝为 INVALID_MESSAGE_ID，重复 messageId 保留首项、后续 DUPLICATE_MESSAGE_ID。 */
    @Test
    void rejectsInvalidAndDuplicateMessageIdPerEntry() {
        UUID messageId = Uuid7.generate();
        byte[] payload = ("{\"devices\":["
                + "{\"messageId\":\"" + messageId + "\",\"deviceKey\":\"sub_01\",\"occurredAt\":\"2026-08-14T08:00:00Z\",\"payload\":{\"t\":1}},"
                + "{\"messageId\":\"not-a-uuid\",\"deviceKey\":\"sub_02\",\"occurredAt\":\"2026-08-14T08:00:01Z\",\"payload\":{\"t\":2}},"
                + "{\"messageId\":\"" + messageId + "\",\"deviceKey\":\"sub_03\",\"occurredAt\":\"2026-08-14T08:00:02Z\",\"payload\":{\"t\":3}}"
                + "]}").getBytes(StandardCharsets.UTF_8);

        GatewayBatchMessage message = normalizer.tryNormalize(rawMessage(payload)).orElseThrow();

        assertThat(message.entries()).hasSize(3);
        assertThat(message.entries().get(0)).isInstanceOf(GatewayBatchMessage.Valid.class);
        assertThat(((GatewayBatchMessage.Rejected) message.entries().get(1)).reason())
                .isEqualTo(GatewayBatchMessage.RejectReason.INVALID_MESSAGE_ID);
        assertThat(((GatewayBatchMessage.Rejected) message.entries().get(2)).reason())
                .isEqualTo(GatewayBatchMessage.RejectReason.DUPLICATE_MESSAGE_ID);
    }

    /** 空 devices 数组属帧级协议错误，进入 DLQ，不得做除零。 */
    @Test
    void rejectsEmptyDevicesAsProtocolError() {
        assertThatThrownBy(() -> normalizer.tryNormalize(rawMessage("{\"devices\":[]}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("devices");
    }

    /** 缺 devices 数组或 payload 不是对象，属帧级结构损坏。 */
    @Test
    void rejectsMissingDevicesOrNonObjectPayload() {
        assertThatThrownBy(() -> normalizer.tryNormalize(rawMessage("{\"foo\":1}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("devices");
        assertThatThrownBy(() -> normalizer.tryNormalize(rawMessage("[1,2,3]".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InvalidUplinkMessageException.class);
    }

    /** 条目缺 deviceKey / occurredAt / payload 属帧级契约损坏，整帧进入 DLQ。 */
    @Test
    void rejectsMalformedEntryFieldsAsProtocolError() {
        byte[] noPayload = ("{\"devices\":[{\"messageId\":\"" + Uuid7.generate()
                + "\",\"deviceKey\":\"sub_01\",\"occurredAt\":\"2026-08-14T08:00:00Z\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> normalizer.tryNormalize(rawMessage(noPayload)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("payload");
    }

    /** 非批量 Topic 返回空，交由属性上报解析器继续处理。 */
    @Test
    void returnsEmptyForNonBatchTopic() {
        RawUplinkMessage raw = new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "tc/v1/project/device/up/property/report", "{}".getBytes(StandardCharsets.UTF_8),
                1, false, "device-client", Instant.now(), "0123456789abcdef0123456789abcdef");
        assertThat(normalizer.tryNormalize(raw)).isEmpty();
    }

    /** @return 指向批量上报 Topic 的原始信封 */
    private static RawUplinkMessage rawMessage(byte[] payload) {
        return rawMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), payload);
    }

    /** 使用调用方指定可信二轴和网关构造原始信封。 */
    private static RawUplinkMessage rawMessage(UUID tenantId, UUID projectId, UUID gatewayId, byte[] payload) {
        return new RawUplinkMessage(tenantId, projectId, gatewayId,
                "tc/v1/project/device/up/batch/report", payload, 1, false,
                "gateway-client", Instant.now(), "0123456789abcdef0123456789abcdef");
    }
}
