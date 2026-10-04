package com.things.link.alarm.application;


import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 告警只读POST在对象绑定前拒绝未知/重复字段、非法UTF-8、null与宽松标量转换。 */
public class AlarmDeviceQueryParser {
    /** 根字段闭集，cursor和limit之外全部必填。 */
    private static final Set<String> ROOT_FIELDS = Set.of("devices", "conditionStates", "ackStates", "severities", "cursor", "limit");
    /** 每个设备对象只允许精确身份和模型预期。 */
    private static final Set<String> DEVICE_FIELDS = Set.of("deviceId", "expectedModelVersionId");
    /** 原始字段重复检测在JsonNode覆盖发生之前执行，尾随值不能被忽略。 */
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** @param source 原始UTF-8 JSON正文 @return 封闭结构请求，业务过滤由应用层再次校验 */
    public AlarmDeviceQueryInput parse(byte[] source) {
        if (source == null || source.length == 0) throw invalid();
        try {
            // CharsetDecoder拒绝非法UTF-8；Jackson负责JSON语法，字段规则只接受规范UUID和枚举机器值。
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
            if (text.charAt(0) == '\uFEFF') throw invalid();
            JsonNode root = mapper.readTree(text);
            requireFields(root, ROOT_FIELDS);
            JsonNode deviceNodes = root.get("devices");
            if (deviceNodes == null || !deviceNodes.isArray() || deviceNodes.isEmpty() || deviceNodes.size() > 20) throw invalid();
            List<AlarmDeviceQueryInput.DeviceRequest> devices = new ArrayList<>();
            for (JsonNode node : deviceNodes) {
                requireFields(node, DEVICE_FIELDS);
                devices.add(new AlarmDeviceQueryInput.DeviceRequest(uuid(node.get("deviceId")), uuid(node.get("expectedModelVersionId"))));
            }
            if (devices.stream().map(AlarmDeviceQueryInput.DeviceRequest::deviceId).distinct().count() != devices.size()) throw invalid();
            List<String> conditions = strings(root.get("conditionStates"), Set.of("PENDING", "ACTIVE", "CLEARED"));
            List<String> acks = strings(root.get("ackStates"), Set.of("UNACKNOWLEDGED", "ACKNOWLEDGED"));
            List<String> severities = strings(root.get("severities"), Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"));
            String cursor = null;
            if (root.has("cursor")) {
                if (!root.get("cursor").isString()) throw invalid();
                cursor = root.get("cursor").asString();
                if (cursor.isEmpty() || cursor.length() > 2048 || cursor.chars().anyMatch(value -> value > 127)) throw invalid();
            }
            Integer limit = null;
            if (root.has("limit")) {
                JsonNode value = root.get("limit");
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 50) throw invalid();
                limit = value.asInt();
            }
            return new AlarmDeviceQueryInput(List.copyOf(devices), conditions, acks, severities, cursor, limit);
        } catch (CharacterCodingException | JacksonException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 根对象或嵌套对象未知字段统一10001，不回显攻击者文本。 */
    private static void requireFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalid();
        for (String field : node.propertyNames()) if (!allowed.contains(field)) throw invalid();
    }

    /** UUID沿元数据合同规范小写形式，拒绝Java UUID解析器的短段兼容行为。 */
    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isString()) throw invalid();
        UUID value = UUID.fromString(node.asString());
        if (!value.toString().equals(node.asString())) throw invalid();
        return value;
    }

    /** 非空、不同且闭集的数组，不能先Set化掩盖重复。 */
    private static List<String> strings(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > allowed.size()) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isString() || !allowed.contains(value.asString()) || values.contains(value.asString())) throw invalid();
            values.add(value.asString());
        }
        return List.copyOf(values);
    }

    /** 封闭读取输入错误与业务配置错误同为10001，不影响登录身份。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
