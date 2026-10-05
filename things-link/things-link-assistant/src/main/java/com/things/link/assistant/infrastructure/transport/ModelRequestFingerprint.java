package com.things.link.assistant.infrastructure.transport;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 按固定发布资源独立重建完整模型请求摘要；不计数、不联网、不授予准入。 */
public final class ModelRequestFingerprint {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String CONTRACT = "/com/things/link/assistant/model-request-contract.json";
    private static final String CONTRACT_SHA256 = "10f0d15475b1beb590311b2d0c0ed3da883226419353360256e22df74243c4e2";
    private static final int MAX_BYTES = 16 * 1024;

    private ModelRequestFingerprint() {}

    /**
     * 从原内部报文取出的受控证据字节计算预期，不能使用内部回执提供的摘要作为预期。
     * @param evidence 已经平台投影及内部编码的原始证据字节；本方法不代替身份和完整证据校验
     * @return 完整固定模型请求的摘要；临时请求字节清零且不发送或保存
     */
    public static String fingerprint(byte[] evidence) {
        byte[] body = null;
        try {
            body = encode(evidence);
            return digest(body);
        } finally {
            if (body != null) Arrays.fill(body, (byte) 0);
        }
    }

    /** 按固定字段顺序编码；用户消息保持原字节对应的文本，不重新序列化数值和时间。 */
    static byte[] encode(byte[] evidence) {
        byte[] result = null;
        try {
            if (evidence == null || evidence.length == 0 || evidence.length > MAX_BYTES) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(evidence)).toString();
            var input = JSON.readTree(text);
            var template = input.get("template");
            if (template == null || !template.isString()) throw invalid();
            String system = contract().get(template.asString());
            if (system == null) throw invalid();
            var body = JSON.createObjectNode();
            body.put("model", "deepseek-flash");
            body.putObject("thinking").put("type", "disabled");
            body.put("stream", false);
            body.put("max_tokens", 1024);
            body.putObject("response_format").put("type", "json_object");
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", text);
            byte[] encoded = JSON.writeValueAsBytes(body);
            if (encoded.length > MAX_BYTES) {
                Arrays.fill(encoded, (byte) 0);
                throw invalid();
            }
            result = encoded;
        } catch (Exception ignored) {
            // 原证据、资源路径及解析原因均不进入错误链。
        }
        if (result == null) throw invalid();
        return result;
    }

    /** 只读固定类路径资源；没有可替换路径、配置或调用方提示词入口。 */
    private static Map<String, String> contract() {
        Map<String, String> result = null;
        byte[] raw = null;
        try (var source = ModelRequestFingerprint.class.getResourceAsStream(CONTRACT)) {
            if (source == null) throw invalid();
            raw = source.readNBytes(MAX_BYTES + 1);
            result = checkedContract(raw);
        } catch (Exception ignored) {
            // 资源缺失或漂移时拒绝，不使用默认提示词继续发送。
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
        if (result == null) throw invalid();
        return result;
    }

    /** 核对固定资源完整摘要及闭集结构，供装配和离线反例共同使用。 */
    static Map<String, String> checkedContract(byte[] raw) {
        Map<String, String> result = null;
        try {
            if (raw == null || raw.length == 0 || raw.length > MAX_BYTES || !CONTRACT_SHA256.equals(digest(raw)))
                throw invalid();
            var root = JSON.readTree(raw);
            if (!root.isObject() || !root.propertyNames().equals(Set.of("version", "model", "promptVersion", "systemMessages"))
                    || !"agent-model-request-binding-v1".equals(root.get("version").asString())
                    || !"deepseek-flash".equals(root.get("model").asString())
                    || !"thingslink-agent-single-analysis-v1".equals(root.get("promptVersion").asString())) throw invalid();
            var messages = root.get("systemMessages");
            if (!messages.isObject() || !messages.propertyNames().equals(Set.of("STATUS_SUMMARY", "ALARM_EXPLANATION")))
                throw invalid();
            for (var message : messages) if (!message.isString() || message.asString().isEmpty()) throw invalid();
            result = Map.of("STATUS_SUMMARY", messages.get("STATUS_SUMMARY").asString(),
                    "ALARM_EXPLANATION", messages.get("ALARM_EXPLANATION").asString());
        } catch (Exception ignored) {
            // 固定发布资源有误时只返回固定错误，不携带提示词正文。
        }
        if (result == null) throw invalid();
        return result;
    }

    /** 对原始字节计算摘要，不把内容或散列算法错误作为异常原因传出。 */
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ignored) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_MODEL_REQUEST_BINDING"); }
}
