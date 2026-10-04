package com.things.link.ingestion.application;

import com.things.link.shared.message.DevicePropertyReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 MQTT 与新协议共用的设备业务载荷读取契约。 */
class DevicePropertyReportReaderTests {

    /** 与 Boot 一致地使用 Jackson 3 默认映射器。 */
    private final DevicePropertyReportReader reader = new DevicePropertyReportReader(new ObjectMapper());

    /** 超出 double 有效精度的十进制必须原样保留，否则跨协议落库数值会静默失真。 */
    @Test
    void preservesDecimalPrecisionBeyondDoubleRange() {
        String json = "{\"messageId\":\"" + com.things.link.shared.id.Uuid7.generate()
                + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\","
                + "\"payload\":{\"energy\":9007199254740993.123456789,\"count\":9007199254740993123456789}}";

        DevicePropertyReport report = reader.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(report.payload().get("energy"))
                .isEqualTo(new BigDecimal("9007199254740993.123456789"));
        assertThat(report.payload().get("count"))
                .isEqualTo(new java.math.BigInteger("9007199254740993123456789"));
    }

    /** 空载荷、非法 JSON 与非对象载荷都属于重放不可恢复的协议错误。 */
    @Test
    void rejectsEmptyAndMalformedPayloadAsPermanentError() {
        assertThatThrownBy(() -> reader.read(null))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> reader.read(new byte[0]))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> reader.read("not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
        assertThatThrownBy(() -> reader.read("[1,2]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
    }

    /** 属性键字符集在读取点统一校验，避免新协议各自放宽。 */
    @Test
    void rejectsIllegalPropertyIdentifier() {
        String json = "{\"messageId\":\"" + com.things.link.shared.id.Uuid7.generate()
                + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\",\"payload\":{\"bad key\":1}}";

        assertThatThrownBy(() -> reader.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");
    }
}
