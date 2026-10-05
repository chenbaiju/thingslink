package com.things.link.enduser.api.support;

import com.things.link.enduser.api.dto.request.WebAppRuntimeDataRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
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

/**
 * App运行数据POST查询的严格原始JSON解析器。
 *
 * <p>数据运行合同把未知/重复/缺失字段、null、错误JSON类型、BOM、非法UTF-8、尾随正文和业务预算
 * 都归一为10001；解析仍分层实现，但不能让Spring宽松DTO绑定吞掉第二组查询计划。</p>
 */
@Component
public final class WebAppRuntimeDataRequestParser {
    /** 快照信封闭集。 */ private static final Set<String> SNAPSHOT_FIELDS = Set.of("models", "devices");
    /** 当前值信封闭集。 */ private static final Set<String> CURRENT_FIELDS = Set.of("devices");
    /** 告警信封闭集。 */ private static final Set<String> ALARM_FIELDS = Set.of(
            "devices", "conditionStates", "ackStates", "severities", "cursor", "limit");
    /** 模型闭集。 */ private static final Set<String> MODEL_FIELDS = Set.of(
            "versionId", "digestAlgorithm", "digest", "profile");
    /** 设备属性请求闭集。 */ private static final Set<String> DEVICE_FIELDS = Set.of(
            "deviceId", "expectedModelVersionId", "propertyKeys");
    /** 告警设备闭集。 */ private static final Set<String> ALARM_DEVICE_FIELDS = Set.of(
            "deviceId", "expectedModelVersionId");
    /** UTF-8 字节顺序标记。 */ private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** token层启用递归重复键拒绝，树层再执行字段闭集。 */
    private final ObjectReader reader = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().reader();

    /** @param source 原始HTTP正文 @return 快照请求 */
    public WebAppRuntimeDataRequest.Snapshot parseSnapshot(byte[] source) {
        JsonNode root = object(source, SNAPSHOT_FIELDS, SNAPSHOT_FIELDS);
        List<WebAppRuntimeDataRequest.Model> models = models(array(root, "models"));
        List<WebAppRuntimeDataRequest.Device> devices = devices(array(root, "devices"), true);
        if (models.size() > 20 || devices.isEmpty() || devices.size() > 20) throw invalid();
        return new WebAppRuntimeDataRequest.Snapshot(models, devices);
    }

    /** @param source 原始HTTP正文 @return 当前值请求 */
    public WebAppRuntimeDataRequest.Current parseCurrent(byte[] source) {
        JsonNode root = object(source, CURRENT_FIELDS, CURRENT_FIELDS);
        List<WebAppRuntimeDataRequest.CurrentDevice> devices = devices(array(root, "devices"), false).stream()
                .map(item -> new WebAppRuntimeDataRequest.CurrentDevice(
                        item.deviceId(), item.expectedModelVersionId(), item.propertyKeys())).toList();
        if (devices.isEmpty() || devices.size() > 20) throw invalid();
        return new WebAppRuntimeDataRequest.Current(devices);
    }

    /** @param source 原始HTTP正文 @return 告警请求 */
    public WebAppRuntimeDataRequest.Alarms parseAlarms(byte[] source) {
        JsonNode root = object(source, ALARM_FIELDS,
                Set.of("devices", "conditionStates", "ackStates", "severities"));
        List<WebAppRuntimeDataRequest.AlarmDevice> devices = alarmDevices(array(root, "devices"));
        Set<String> conditions = uniqueStrings(array(root, "conditionStates"));
        Set<String> acknowledgements = uniqueStrings(array(root, "ackStates"));
        Set<String> severities = uniqueStrings(array(root, "severities"));
        String cursor = root.has("cursor") ? string(root, "cursor") : null;
        Integer limit = root.has("limit") ? integer(root, "limit") : null;
        if (devices.isEmpty() || devices.size() > 20 || conditions.isEmpty()
                || acknowledgements.isEmpty() || severities.isEmpty()) throw invalid();
        return new WebAppRuntimeDataRequest.Alarms(
                devices, conditions, acknowledgements, severities, cursor, limit);
    }

    /** 解析模型数组并拒绝重复版本。 */
    private static List<WebAppRuntimeDataRequest.Model> models(JsonNode nodes) {
        List<WebAppRuntimeDataRequest.Model> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, MODEL_FIELDS, MODEL_FIELDS);
            UUID id = uuid(string(node, "versionId"));
            if (!ids.add(id)) throw invalid();
            result.add(new WebAppRuntimeDataRequest.Model(id, string(node, "digestAlgorithm"),
                    string(node, "digest"), string(node, "profile")));
        }
        return List.copyOf(result);
    }

    /** 解析设备属性请求并执行组合预算。 */
    private static List<WebAppRuntimeDataRequest.Device> devices(JsonNode nodes, boolean allowEmptyKeys) {
        List<WebAppRuntimeDataRequest.Device> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        int combinations = 0;
        for (JsonNode node : nodes) {
            requireObject(node, DEVICE_FIELDS, DEVICE_FIELDS);
            UUID id = uuid(string(node, "deviceId"));
            UUID model = uuid(string(node, "expectedModelVersionId"));
            List<String> keys = stringList(array(node, "propertyKeys"));
            if (!ids.add(id) || (!allowEmptyKeys && keys.isEmpty()) || keys.size() > 50
                    || new HashSet<>(keys).size() != keys.size()
                    || keys.stream().anyMatch(key -> !key.matches("[A-Za-z0-9_-]{1,64}"))) throw invalid();
            combinations += keys.size();
            result.add(new WebAppRuntimeDataRequest.Device(id, model, keys));
        }
        if (combinations > 200) throw invalid();
        return List.copyOf(result);
    }

    /** 解析告警设备集合并拒绝重复。 */
    private static List<WebAppRuntimeDataRequest.AlarmDevice> alarmDevices(JsonNode nodes) {
        List<WebAppRuntimeDataRequest.AlarmDevice> result = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            requireObject(node, ALARM_DEVICE_FIELDS, ALARM_DEVICE_FIELDS);
            UUID id = uuid(string(node, "deviceId"));
            if (!ids.add(id)) throw invalid();
            result.add(new WebAppRuntimeDataRequest.AlarmDevice(
                    id, uuid(string(node, "expectedModelVersionId"))));
        }
        return List.copyOf(result);
    }

    /** 严格读取根对象并拒绝宽松编码。 */
    private JsonNode object(byte[] source, Set<String> allowed, Set<String> required) {
        if (source == null || source.length == 0 || startsWithBom(source)) throw malformed();
        try {
            JsonNode root = reader.readTree(decodeUtf8(source));
            requireObject(root, allowed, required);
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw malformed();
        }
    }

    /** 要求对象字段属于闭集并包含全部必填字段。 */
    private static void requireObject(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw malformed();
        Set<String> names = Set.copyOf(node.propertyNames());
        if (!allowed.containsAll(names) || !names.containsAll(required)) throw malformed();
    }

    /** 要求数组字段存在且类型准确。 */
    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw malformed();
        return value;
    }

    /** 要求字符串字段存在、非null且Unicode标量合法。 */
    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw malformed();
        String text = value.asString();
        requireUnicode(text);
        return text;
    }

    /** 要求JSON整数可无损转int。 */
    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw malformed();
        return value.asInt();
    }

    /** 读取字符串数组，元素类型错误属于外层10002。 */
    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isString()) throw malformed();
            String text = value.asString();
            requireUnicode(text);
            result.add(text);
        }
        return List.copyOf(result);
    }

    /** 读取非空唯一字符串集合；重复是业务集合错误10001。 */
    private static Set<String> uniqueStrings(JsonNode node) {
        List<String> values = stringList(node);
        Set<String> result = Set.copyOf(values);
        if (result.size() != values.size()) throw invalid();
        return result;
    }

    /** 解析规范小写UUID，不接受trim、大写或宽松缩写。 */
    private static UUID uuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw new IllegalArgumentException("UUID非规范文本");
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 以REPORT模式关闭UTF-16/32自动探测与替换字符恢复。 */
    private static String decodeUtf8(byte[] source) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        } catch (CharacterCodingException exception) {
            throw malformed();
        }
    }

    /** 检测被禁止的UTF-8 BOM。 */
    private static boolean startsWithBom(byte[] source) {
        return source.length >= 3 && source[0] == BOM[0] && source[1] == BOM[1] && source[2] == BOM[2];
    }

    /** 拒绝U+0000和未配对代理项。 */
    private static void requireUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') throw malformed();
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw malformed();
            } else if (Character.isLowSurrogate(current)) {
                throw malformed();
            }
        }
    }

    /** @return 数据查询严格信封错误10001 */
    private static BusinessException malformed() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /** @return 业务语法、数量或重复错误10001 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
