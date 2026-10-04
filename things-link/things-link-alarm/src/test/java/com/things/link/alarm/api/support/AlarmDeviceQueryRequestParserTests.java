package com.things.link.alarm.api.support;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Console告警POST的最低层JSON闭集测试，避免DTO绑定提前吞掉重复字段或非法标量。 */
class AlarmDeviceQueryRequestParserTests {
    /** 无业务依赖的原始信封解析器。 */
    private final AlarmDeviceQueryRequestParser parser = new AlarmDeviceQueryRequestParser();
    /** 构造规范JSON夹具，不替代被测原始字节解析。 */
    private final JsonMapper json = JsonMapper.builder().build();
    /** 稳定设备身份便于插入重复反例。 */
    private final UUID device = UUID.randomUUID();
    /** 预期模型只作为不透明UUID。 */
    private final UUID model = UUID.randomUUID();

    /** 合法最小输入保留省略字段，由业务层填默认页大小。 */
    @Test
    void preservesCanonicalRequestAndOptionalDefaults() {
        var result = parser.parse(bytes(valid().toString()));
        assertThat(result.devices()).hasSize(1);
        assertThat(result.devices().getFirst().deviceId()).isEqualTo(device);
        assertThat(result.devices().getFirst().expectedModelVersionId()).isEqualTo(model);
        assertThat(result.cursor()).isNull();
        assertThat(result.limit()).isNull();
        assertThat(result.conditionStates()).containsExactly("ACTIVE");
    }

    /** 结构错误必须10001，不是空页或读取默认全部条件。 */
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "NESTED_UNKNOWN", "MISSING", "NULL_DEVICES", "EMPTY_DEVICES", "DUPLICATE_DEVICE",
            "DEVICE_UUID", "MODEL_UUID", "EMPTY_FILTER", "DUPLICATE_FILTER", "UNKNOWN_FILTER", "NULL_FILTER",
            "CURSOR_NULL", "CURSOR_EMPTY", "CURSOR_UNICODE", "CURSOR_LONG", "LIMIT_ZERO", "LIMIT_LARGE", "LIMIT_STRING", "LIMIT_DECIMAL", "LIMIT_NULL"})
    void rejectsInvalidShapeBeforeBinding(String change) {
        ObjectNode body = valid();
        switch (change) {
            case "UNKNOWN" -> body.put("unknown", true);
            case "NESTED_UNKNOWN" -> ((ObjectNode) body.path("devices").get(0)).put("unknown", true);
            case "MISSING" -> body.remove("devices");
            case "NULL_DEVICES" -> body.putNull("devices");
            case "EMPTY_DEVICES" -> body.putArray("devices");
            case "DUPLICATE_DEVICE" -> ((tools.jackson.databind.node.ArrayNode) body.path("devices")).add(body.path("devices").get(0).deepCopy());
            case "DEVICE_UUID" -> ((ObjectNode) body.path("devices").get(0)).put("deviceId", "1-1-1-1-1");
            case "MODEL_UUID" -> ((ObjectNode) body.path("devices").get(0)).put("expectedModelVersionId", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA");
            case "EMPTY_FILTER" -> body.putArray("conditionStates");
            case "DUPLICATE_FILTER" -> body.putArray("conditionStates").add("ACTIVE").add("ACTIVE");
            case "UNKNOWN_FILTER" -> body.putArray("ackStates").add("UNKNOWN");
            case "NULL_FILTER" -> body.putNull("severities");
            case "CURSOR_NULL" -> body.putNull("cursor");
            case "CURSOR_EMPTY" -> body.put("cursor", "");
            case "CURSOR_UNICODE" -> body.put("cursor", "游标");
            case "CURSOR_LONG" -> body.put("cursor", "a".repeat(2049));
            case "LIMIT_ZERO" -> body.put("limit", 0);
            case "LIMIT_LARGE" -> body.put("limit", 51);
            case "LIMIT_STRING" -> body.put("limit", "20");
            case "LIMIT_DECIMAL" -> body.put("limit", 20.0);
            case "LIMIT_NULL" -> body.putNull("limit");
            default -> throw new AssertionError(change);
        }
        assertInvalid(bytes(body.toString()));
    }

    /** 重复字段、解码后重复名、尾随值、非法UTF-8及BOM都不能先被对象树吞掉。 */
    @Test
    void rejectsRawDuplicateAndEncodingCounterexamples() {
        String body = valid().toString();
        assertInvalid(bytes(body.substring(0, body.length() - 1) + ",\"devices\":[]}"));
        assertInvalid(bytes(body.substring(0, body.length() - 1) + ",\"devic\\u0065s\":[]}"));
        assertInvalid(bytes(body + " {}"));
        assertInvalid(bytes("\uFEFF" + body));
        assertInvalid(new byte[]{(byte) 0xc3, (byte) 0x28});
    }

    /** 请求最大设备数20仍合法，21明确拒绝而不裁剪。 */
    @Test
    void enforcesDeviceCountWithoutTruncation() {
        ObjectNode body = valid();
        var devices = body.putArray("devices");
        for (int index = 0; index < 20; index++) {
            devices.addObject().put("deviceId", UUID.randomUUID().toString()).put("expectedModelVersionId", model.toString());
        }
        assertThat(parser.parse(bytes(body.toString())).devices()).hasSize(20);
        devices.addObject().put("deviceId", UUID.randomUUID().toString()).put("expectedModelVersionId", model.toString());
        assertInvalid(bytes(body.toString()));
    }

    /** 完整四必填字段，设备和模型均为规范小写UUID。 */
    private ObjectNode valid() {
        ObjectNode body = json.createObjectNode();
        body.putArray("devices").addObject().put("deviceId", device.toString()).put("expectedModelVersionId", model.toString());
        body.putArray("conditionStates").add("ACTIVE");
        body.putArray("ackStates").add("UNACKNOWLEDGED");
        body.putArray("severities").add("MAJOR");
        return body;
    }
    /** 唯一受测入口是原始UTF-8字节。 */
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    /** 闭集错误统一10001。 */
    private void assertInvalid(byte[] source) {
        assertThatThrownBy(() -> parser.parse(source)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
    }
}
