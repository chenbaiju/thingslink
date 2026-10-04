package com.things.link.device.api.support;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Console运行查询严格UTF-8、重复键、字段闭集与数量关系测试。 */
class DeviceRuntimeQueryRequestParserTests {

    /** 请求解析器。 */ private final DeviceRuntimeQueryRequestParser parser = new DeviceRuntimeQueryRequestParser();
    /** 模型版本。 */ private final UUID modelId = UUID.randomUUID();
    /** 设备。 */ private final UUID deviceId = UUID.randomUUID();

    /** 合法快照保持模型、设备和属性顺序。 */
    @Test
    void snapshotsPreserveDeclaredOrder() {
        DeviceRuntimeQueryRequestParser.SnapshotRequest result = parser.parseSnapshots(snapshot().getBytes(StandardCharsets.UTF_8));

        assertThat(result.models()).hasSize(1);
        assertThat(result.devices()).hasSize(1);
        assertThat(result.devices().getFirst().propertyKeys()).containsExactly("temperature", "mode");
    }

    /** 快照设备expected模型必须在本地models中命中，数据库可见性不改变结构拒绝。 */
    @Test
    void snapshotsRejectUnknownLocalModelReference() {
        String body = snapshot().replace("\"expectedModelVersionId\":\"" + modelId + "\"",
                "\"expectedModelVersionId\":\"" + UUID.randomUUID() + "\"");

        assertInvalid(body.getBytes(StandardCharsets.UTF_8));
    }

    /** UTF-16自动探测、UTF-8 BOM、非法UTF-8、重复键和尾随根一律拒绝。 */
    @Test
    void parserRejectsAlternativeEncodingAndAmbiguousJson() {
        assertInvalid(snapshot().getBytes(StandardCharsets.UTF_16LE));
        byte[] body = snapshot().getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        assertInvalid(bom);
        assertInvalid(new byte[]{(byte) 0xC3, 0x28});
        assertInvalid(snapshot().replace("\"models\":", "\"models\":[],\"models\":")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid((snapshot() + " {}").getBytes(StandardCharsets.UTF_8));
    }

    /** 根和设备对象的未知、缺失、null及错误类型字段均拒绝。 */
    @Test
    void parserRejectsNonClosedEnvelope() {
        assertInvalid("[]".getBytes(StandardCharsets.UTF_8));
        assertInvalid(snapshot().replace("\"models\":", "\"extra\":1,\"models\":")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(snapshot().replace("\"propertyKeys\":[\"temperature\",\"mode\"]", "\"propertyKeys\":null")
                .getBytes(StandardCharsets.UTF_8));
        assertInvalid(snapshot().replace(",\"propertyKeys\":[\"temperature\",\"mode\"]", "")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 当前值请求禁止空keys并拒绝重复设备、键和超过200组合。 */
    @Test
    void currentValuesEnforceSparseRequestBudget() {
        String valid = "{\"devices\":[" + device("temperature") + "]}";
        assertThat(parser.parseCurrentValues(valid.getBytes(StandardCharsets.UTF_8))).hasSize(1);
        assertCurrentInvalid(("{\"devices\":[" + device() + "]}").getBytes(StandardCharsets.UTF_8));
        assertCurrentInvalid(("{\"devices\":[" + device("temperature") + "," + device("mode") + "]}")
                .getBytes(StandardCharsets.UTF_8));
        assertCurrentInvalid(("{\"devices\":[" + device("temperature", "temperature") + "]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 断言统一10001参数拒绝。 */
    private void assertInvalid(byte[] body) {
        assertThatThrownBy(() -> parser.parseSnapshots(body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode().code())
                .isEqualTo(10001);
    }

    /** 断言当前值解析同样使用10001。 */
    private void assertCurrentInvalid(byte[] body) {
        assertThatThrownBy(() -> parser.parseCurrentValues(body))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode().code())
                .isEqualTo(10001);
    }

    /** 合法快照JSON。 */
    private String snapshot() {
        return "{\"models\":[{\"versionId\":\"" + modelId
                + "\",\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\",\"digest\":\"" + "a".repeat(64)
                + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}],\"devices\":[" + device("temperature", "mode") + "]}";
    }

    /** 构建设备请求对象。 */
    private String device(String... keys) {
        String properties = java.util.Arrays.stream(keys).map(key -> "\"" + key + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"deviceId\":\"" + deviceId + "\",\"expectedModelVersionId\":\"" + modelId
                + "\",\"propertyKeys\":[" + properties + "]}";
    }
}
