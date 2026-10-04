package com.things.link.device.application;

import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 无身份假设的严格设备快照与当前值查询信封，保留重复键和非UTF-8拒绝证据。 */
public class RuntimeDeviceQueryParser {

    /** 启用重复键拒绝且不启用任何非标准JSON扩展。 */
    private final ObjectReader reader = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build().reader();

    /** @param source 原始UTF-8请求体 @return 快照模型与设备请求 */
    public SnapshotRequest parseSnapshots(byte[] source) {
        JsonNode root = root(source, Set.of("models", "devices"));
        List<RuntimeModelReference> models = models(requiredArray(root, "models"));
        List<RuntimeDeviceQuery> devices = devices(requiredArray(root, "devices"), true);
        Set<UUID> modelIds = new HashSet<>();
        models.forEach(model -> modelIds.add(model.versionId()));
        if (devices.stream().anyMatch(device -> !modelIds.contains(device.expectedModelVersionId()))) throw invalid();
        return new SnapshotRequest(models, devices);
    }

    /** @param source 原始UTF-8请求体 @return 当前值设备请求 */
    public List<RuntimeDeviceQuery> parseCurrentValues(byte[] source) {
        JsonNode root = root(source, Set.of("devices"));
        return devices(requiredArray(root, "devices"), false);
    }

    /** 严格证明UTF-8和根字段闭集后建立JSON树。 */
    private JsonNode root(byte[] source, Set<String> fields) {
        if (source == null || source.length == 0 || hasUtf8Bom(source)) throw invalid();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
        if (text.indexOf('\u0000') >= 0 || text.chars().anyMatch(value -> Character.isSurrogate((char) value))) {
            throw invalid();
        }
        JsonNode root;
        try {
            root = reader.readTree(text);
        } catch (RuntimeException exception) {
            throw invalid();
        }
        if (root == null || !root.isObject() || root.size() != fields.size()
                || root.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw invalid();
        }
        return root;
    }

    /** 快照models保持请求顺序并只接受四字段字符串对象。 */
    private static List<RuntimeModelReference> models(JsonNode values) {
        if (values.size() > 20) throw invalid();
        List<RuntimeModelReference> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode value : values) {
            requireFields(value, Set.of("versionId", "digestAlgorithm", "digest", "profile"));
            UUID id = uuid(requiredText(value, "versionId"));
            if (!ids.add(id)) throw invalid();
            result.add(new RuntimeModelReference(id, requiredText(value, "digestAlgorithm"),
                    requiredText(value, "digest"), requiredText(value, "profile")));
        }
        return List.copyOf(result);
    }

    /** 设备请求保持双层顺序，结构数量由应用服务再次防御。 */
    private static List<RuntimeDeviceQuery> devices(JsonNode values, boolean allowEmptyProperties) {
        if (values.isEmpty() || values.size() > 20) throw invalid();
        List<RuntimeDeviceQuery> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        int combinations = 0;
        for (JsonNode value : values) {
            requireFields(value, Set.of("deviceId", "expectedModelVersionId", "propertyKeys"));
            UUID deviceId = uuid(requiredText(value, "deviceId"));
            if (!ids.add(deviceId)) throw invalid();
            JsonNode keys = requiredArray(value, "propertyKeys");
            if (!allowEmptyProperties && keys.isEmpty() || keys.size() > 50) throw invalid();
            List<String> properties = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode key : keys) {
                if (!key.isString() || !key.asString().matches("[A-Za-z0-9_-]{1,64}")
                        || !unique.add(key.asString())) throw invalid();
                properties.add(key.asString());
            }
            combinations += properties.size();
            result.add(new RuntimeDeviceQuery(deviceId, uuid(requiredText(value, "expectedModelVersionId")), properties));
        }
        if (combinations > 200) throw invalid();
        return List.copyOf(result);
    }

    /** 要求对象字段集合精确一致。 */
    private static void requireFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()
                || value.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) throw invalid();
    }

    /** 读取必填数组。 */
    private static JsonNode requiredArray(JsonNode value, String field) {
        JsonNode result = value.get(field);
        if (result == null || !result.isArray()) throw invalid();
        return result;
    }

    /** 读取必填非null字符串。 */
    private static String requiredText(JsonNode value, String field) {
        JsonNode result = value.get(field);
        if (result == null || !result.isString()) throw invalid();
        return result.asString();
    }

    /** UUID必须为规范小写36字符文本，不接受宽松缩写或大写。 */
    private static UUID uuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** UTF-8 BOM不属于请求JSON正文。 */
    private static boolean hasUtf8Bom(byte[] source) {
        return source.length >= 3 && source[0] == (byte) 0xEF
                && source[1] == (byte) 0xBB && source[2] == (byte) 0xBF;
    }

    /** @return 统一10001结构或参数拒绝 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** @param models 模型身份 @param devices 设备及属性请求 */
    public record SnapshotRequest(List<RuntimeModelReference> models, List<RuntimeDeviceQuery> devices) {
        /** 冻结严格解析结果。 */
        public SnapshotRequest {
            models = List.copyOf(models);
            devices = List.copyOf(devices);
        }
    }
}
