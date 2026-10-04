package com.things.link.ingestion.application;

import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.SubDeviceReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 把网关的 {@code up/batch/report} 报文解析为 {@link GatewayBatchMessage}。
 *
 * <p>归属字段（tenant/project/gateway）只采用 raw 信封已确权的身份；payload 仅提供子设备条目，
 * 禁止自报网关。帧级结构损坏（非对象、缺 {@code devices} 数组、空数组、条目缺 {@code deviceKey} /
 * {@code occurredAt} / {@code payload}）直接抛 {@link InvalidUplinkMessageException} 进入 DLQ；
 * 只有条目 {@code messageId} 缺失/非法/重复按条目拒绝（部分处理），不拖垮整帧。</p>
 */
public class GatewayBatchMessageNormalizer {

    /** 帧级结构拒绝的字节计量；frameBytes 全量计入，避免均分后凭空消失。 */
    public static final String FRAME_REJECTED_BYTES = "device_batch_frame_rejected_bytes";

    /** 批量上报的 §5.3 上行 Topic 类型。 */
    private static final String BATCH_REPORT = "batch/report";

    /** 由Boot映射器派生的局部精确副本；原字节树和Map两层必须使用相同十进制规则。 */
    private final ObjectMapper objectMapper;

    /** 帧级结构拒绝字节计数器；只允许低基数字节标签。 */
    private final Counter frameRejectedBytes;

    /**
     * @param objectMapper 应用统一 JSON 映射器
     * @param meterRegistry Micrometer 注册表
     */
    public GatewayBatchMessageNormalizer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        // D-146：默认树节点一旦先舍入，后续Map或Kafka精确重读也无法还原原文。
        this.objectMapper = objectMapper.rebuild()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        this.frameRejectedBytes = Counter.builder(FRAME_REJECTED_BYTES)
                .description("批量上报帧级结构拒绝的原始字节数")
                .register(meterRegistry);
    }

    /**
     * 解析批量上报消息；非批量类型返回空，由 {@link RawUplinkMessageNormalizer} 继续处理。
     *
     * @param raw 接入层确权后的原始信封
     * @return 批量消息信封，非批量类型时为空
     * @throws InvalidUplinkMessageException 帧级结构违反批量报文契约时抛出（进入 DLQ）
     */
    public Optional<GatewayBatchMessage> tryNormalize(RawUplinkMessage raw) {
        MqttUplinkTopic topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null || !BATCH_REPORT.equals(topic.messageType())) {
            return Optional.empty();
        }
        int frameBytes = raw.payload().length;
        try {
            return Optional.of(parse(raw, frameBytes));
        } catch (InvalidUplinkMessageException exception) {
            frameRejectedBytes.increment(frameBytes);
            throw exception;
        }
    }

    /** 解析已判定为批量上报的原始报文。 */
    private GatewayBatchMessage parse(RawUplinkMessage raw, int frameBytes) {
        JsonNode root;
        try {
            // 架构第5节：从原始字节直接形成DecimalNode，禁止先经默认DoubleNode。
            root = objectMapper.readTree(raw.payload());
        } catch (JacksonException exception) {
            throw new InvalidUplinkMessageException("批量上报 payload 不是合法 JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidUplinkMessageException("批量上报 payload 必须是 JSON 对象");
        }
        JsonNode devices = root.get("devices");
        if (devices == null || !devices.isArray() || devices.isEmpty()) {
            throw new InvalidUplinkMessageException("批量上报必须携带非空 devices 数组");
        }

        int count = devices.size();
        int base = frameBytes / count;
        int remainder = frameBytes % count;
        List<GatewayBatchMessage.Entry> entries = new ArrayList<>(count);
        Set<UUID> seenMessageIds = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int rawBytes = base + (index < remainder ? 1 : 0);
            entries.add(normalizeEntry(devices.get(index), rawBytes, seenMessageIds));
        }
        return new GatewayBatchMessage(raw.tenantId(), raw.projectId(), raw.deviceId(), frameBytes,
                raw.receivedAt(), raw.traceId(), entries);
    }

    /** 归一化单个子设备条目：messageId 非法/重复按条目拒绝，其余字段严格按契约。 */
    private GatewayBatchMessage.Entry normalizeEntry(JsonNode node, int rawBytes, Set<UUID> seenMessageIds) {
        if (node == null || !node.isObject()) {
            throw new InvalidUplinkMessageException("批量上报条目必须是 JSON 对象");
        }
        UUID messageId = uuidV7OrNull(node.get("messageId"));
        if (messageId == null) {
            return new GatewayBatchMessage.Rejected(
                    GatewayBatchMessage.RejectReason.INVALID_MESSAGE_ID, rawBytes);
        }
        if (!seenMessageIds.add(messageId)) {
            return new GatewayBatchMessage.Rejected(
                    GatewayBatchMessage.RejectReason.DUPLICATE_MESSAGE_ID, rawBytes);
        }
        try {
            SubDeviceReport report = new SubDeviceReport(messageId, text(node.get("deviceKey")),
                    occurredAt(node.get("occurredAt")), text(node.get("modelVersion")),
                    payload(node.get("payload")));
            return new GatewayBatchMessage.Valid(report, rawBytes);
        } catch (IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("批量上报条目不符合子设备上报契约: " + exception.getMessage(),
                    exception);
        }
    }

    /** @return 合法 UUIDv7，否则 null（缺失/非文本/非法/版本位错误都按条目拒绝） */
    private static UUID uuidV7OrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            UUID value = UUID.fromString(node.asText());
            return value.version() == 7 ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** @return 文本字段，缺失或非文本返回 null，由下游严格校验 */
    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asString();
    }

    /** @return RFC3339 UTC 时刻，缺失或格式非法抛契约错误 */
    private static Instant occurredAt(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("occurredAt 不是 RFC3339 UTC 时刻", exception);
        }
    }

    /** @return 非空属性对象，否则抛契约错误 */
    private Map<String, Object> payload(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException("payload 必须是非空对象");
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
    }
}
