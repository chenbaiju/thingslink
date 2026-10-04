package com.things.link.ingestion.application;

import org.springframework.stereotype.Component;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import tools.jackson.core.StreamReadFeature;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 严格解析 Broker 信封；未知、缺失或类型漂移字段全部按 poison 处理。 */
@Component
public class BrokerHandoffEnvelopeParser {

    /** v1 精确字段集合；允许未知字段会让新旧 ingress 对同一字节产生不同解释。 */
    private static final Set<String> FIELDS = Set.of("schemaVersion", "handoffId", "username", "topic",
            "payloadBase64", "qos", "retained", "clientId", "publishedAtMs", "brokerNode");
    /** 交接信封只承载一条 MQTT 消息，限制解析体积以免内部配置错误耗尽堆。 */
    private static final int MAX_ENVELOPE_BYTES = 1_048_576;
    /** Boot 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper Boot 统一 JSON 映射器 */
    public BrokerHandoffEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * @param bytes republish 到内部 Topic 的原始字节
     * @return 仅当版本、字段、类型与传输不变量全部精确成立时返回信封
     */
    public Optional<BrokerHandoffEnvelope> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENVELOPE_BYTES) return Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject() || !exactFields(root, root.path("schemaVersion").asInt())
                    || !root.get("schemaVersion").isIntegralNumber()
                    || !root.get("schemaVersion").canConvertToInt()
                    || (root.get("schemaVersion").asInt() != 1 && root.get("schemaVersion").asInt() != 2)
                    || !root.get("qos").isIntegralNumber() || !root.get("qos").canConvertToInt() || root.get("qos").asInt() != 1
                    || !root.get("retained").isBoolean() || root.get("retained").asBoolean()
                    || !root.get("publishedAtMs").isIntegralNumber()
                    || !root.get("publishedAtMs").canConvertToLong() || root.get("publishedAtMs").asLong() < 0) {
                return Optional.empty();
            }
            String handoffId = text(root, "handoffId", 1, 128);
            String username = text(root, "username", 1, 129);
            String topic = text(root, "topic", 1, 512);
            String payloadBase64 = text(root, "payloadBase64", 1, MAX_ENVELOPE_BYTES);
            String clientId = text(root, "clientId", 1, 256);
            String brokerNode = text(root, "brokerNode", 1, 256);
            if (!printableAscii(handoffId) || username == null || topic == null || payloadBase64 == null
                    || clientId == null || brokerNode == null) return Optional.empty();
            // 解码只验证标准 Base64 与非空载荷；业务服务仍从冻结 request DTO 重新解码并确权。
            if (Base64.getDecoder().decode(payloadBase64).length == 0) return Optional.empty();
            return Optional.of(new BrokerHandoffEnvelope(handoffId, username, topic, payloadBase64, 1, false,
                    clientId, root.get("publishedAtMs").asLong(), brokerNode, identity(root.get("authenticatedIdentity"))));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /** 精确比较字段名，禁止同名之外的信封扩展静默穿过旧消费者。 */
    private static boolean exactFields(JsonNode root, int version) {
        Set<String> actual = new HashSet<>();
        root.propertyNames().forEach(actual::add);
        Set<String> expected = new HashSet<>(FIELDS);
        if (version == 2) expected.add("authenticatedIdentity");
        return actual.equals(expected);
    }

    /** 认证身份只取Broker专用对象，四字段必须完整且全部为规范字符串。 */
    private static AuthenticatedDeviceIdentity identity(JsonNode node) {
        if (node == null || node.isNull()) return null;
        Set<String> fields = new HashSet<>();
        node.propertyNames().forEach(fields::add);
        if (!node.isObject()
                || !fields.equals(Set.of("tenantId", "projectId", "deviceId", "credentialVersion"))) {
            throw new IllegalArgumentException("认证身份字段不完整");
        }
        UUID tenant = identityUuid(node, "tenantId");
        UUID project = identityUuid(node, "projectId");
        UUID device = identityUuid(node, "deviceId");
        String version = text(node, "credentialVersion", 1, 19);
        if (version == null || !version.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("认证代际不规范");
        }
        return new AuthenticatedDeviceIdentity(tenant, project, device, Long.parseLong(version));
    }

    /** UUID只接受标准小写编码，不做宽松补零或大小写归一化。 */
    private static UUID identityUuid(JsonNode node, String field) {
        String value = text(node, field, 36, 36);
        if (value == null) throw new IllegalArgumentException("认证身份不规范");
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("认证身份不规范");
        return id;
    }

    /** 读取有界 UTF-8 文本；JSON 非文本、空白或超长均拒绝。 */
    private static String text(JsonNode root, String field, int minimumBytes, int maximumBytes) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return text.isBlank() || bytes < minimumBytes || bytes > maximumBytes ? null : text;
    }

    /** handoffId 被写入证据，故只允许不会破坏 JSONL/日志边界的可打印 ASCII。 */
    private static boolean printableAscii(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x21 || current > 0x7e) return false;
        }
        return true;
    }
}
