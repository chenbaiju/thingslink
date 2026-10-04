package com.things.link.dashboard.api.support;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三个运行查询信封的字段闭集、重复键和业务预算纯测试。 */
class DashboardShareDataRequestParserTests {
    /** 固定模型UUID。 */ private static final String MODEL = "00000000-0000-0000-0000-000000000101";
    /** 固定设备UUID。 */ private static final String DEVICE = "00000000-0000-0000-0000-000000000201";
    /** 被测严格解析器。 */ private final DashboardShareDataRequestParser parser = new DashboardShareDataRequestParser();

    /** 快照保留请求顺序并接受空属性键。 */
    @Test
    void parsesSnapshotClosedEnvelope() {
        var result = parser.parseSnapshot(bytes("""
                {"models":[{"versionId":"%s","digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256",
                "digest":"%s","profile":"TC_PROPERTY_COMPOSITE_V1"}],
                "devices":[{"deviceId":"%s","expectedModelVersionId":"%s","propertyKeys":[]}]}
                """.formatted(MODEL, "a".repeat(64), DEVICE, MODEL)));
        assertThat(result.models()).hasSize(1);
        assertThat(result.devices().getFirst().propertyKeys()).isEmpty();
    }

    /** 本地expected引用未命中models属于请求结构错误，不能延后为403能力范围判定。 */
    @Test
    void rejectsDanglingModelReference() {
        assertCode(() -> parser.parseSnapshot(bytes("""
                {"models":[],"devices":[{"deviceId":"%s","expectedModelVersionId":"%s","propertyKeys":[]}]}
                """.formatted(DEVICE, MODEL))), 10001);
    }

    /** 当前值拒绝重复嵌套键、未知字段和空属性集合。 */
    @Test
    void rejectsDuplicateUnknownAndEmptyCurrentPlans() {
        assertCode(() -> parser.parseCurrent(bytes("""
                {"devices":[{"deviceId":"%s","deviceId":"%s",
                "expectedModelVersionId":"%s","propertyKeys":["temperature"]}]}
                """.formatted(DEVICE, DEVICE, MODEL))), 10001);
        assertCode(() -> parser.parseCurrent(bytes("""
                {"devices":[{"deviceId":"%s","expectedModelVersionId":"%s",
                "propertyKeys":["temperature"],"probe":true}]}
                """.formatted(DEVICE, MODEL))), 10001);
        assertCode(() -> parser.parseCurrent(bytes("""
                {"devices":[{"deviceId":"%s","expectedModelVersionId":"%s","propertyKeys":[]}]}
                """.formatted(DEVICE, MODEL))), 10001);
    }

    /** 告警可省略cursor/limit，默认值由Controller决定。 */
    @Test
    void parsesAlarmFiltersWithoutOptionalPageFields() {
        var result = parser.parseAlarms(bytes("""
                {"devices":[{"deviceId":"%s","expectedModelVersionId":"%s"}],
                "conditionStates":["ACTIVE"],"ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]}
                """.formatted(DEVICE, MODEL)));
        assertThat(result.cursor()).isNull();
        assertThat(result.limit()).isNull();
    }

    /** 非UTF-8、BOM、尾随值和错误JSON类型统一10001。 */
    @Test
    void rejectsNonCanonicalJsonEnvelope() {
        assertCode(() -> parser.parseSnapshot(new byte[]{(byte) 0xC3, 0x28}), 10001);
        assertCode(() -> parser.parseSnapshot(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}), 10001);
        assertCode(() -> parser.parseAlarms(bytes("{}{}")), 10001);
        assertCode(() -> parser.parseAlarms(bytes("{\"devices\":null}")), 10001);
    }

    /** UTF-8编码测试正文。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 断言公开错误码。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode().code()).isEqualTo(code);
    }
}
