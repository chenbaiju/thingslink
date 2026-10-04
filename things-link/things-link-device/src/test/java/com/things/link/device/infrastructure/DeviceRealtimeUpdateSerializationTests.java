package com.things.link.device.infrastructure;

import com.things.link.shared.message.DeviceRealtimeUpdate;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实时接受顺序的跨进程兼容边界，不以desired版本补造旧序号。 */
class DeviceRealtimeUpdateSerializationTests {
    /** 大于JS安全整数的序号与模型来源必须穿过真实JSON序列化而保持文本身份。 */
    @Test
    void roundTripKeepsLongRevisionTextAndSource() {
        JsonMapper mapper = JsonMapper.builder().build();
        DeviceRealtimeUpdate update = update(Map.of("temperature", "9007199254740993"));
        String json = mapper.writeValueAsString(update);
        assertThat(mapper.readTree(json).get("reportedRevisions").get("temperature").isString()).isTrue();
        assertThat(mapper.readValue(json, DeviceRealtimeUpdate.class)).isEqualTo(update);
    }

    /** 旧生产者没有新增字段时只能得到未知，不能把shadowVersion解释为接受序号。 */
    @Test
    void oldEnvelopeDoesNotInventReportedRevision() {
        JsonMapper mapper = JsonMapper.builder().build();
        DeviceRealtimeUpdate update = update(Map.of("temperature", "9"));
        ObjectNode oldJson = (ObjectNode) mapper.readTree(mapper.writeValueAsString(update));
        oldJson.remove("reportedRevisions");
        DeviceRealtimeUpdate restored = mapper.treeToValue(oldJson, DeviceRealtimeUpdate.class);
        assertThat(restored.reportedRevisions()).isEmpty();
        assertThat(restored.shadowVersion()).isEqualTo(7);
        assertThat(restored.thingModelVersionId()).isEqualTo(update.thingModelVersionId());
    }

    /** 非规范、溢出和额外属性必须拒绝，不能生成可广播的错误水位。 */
    @Test
    void invalidRevisionsAreRejected() {
        for (String invalid : new String[]{"0", "-1", "01", "1.0", "9223372036854775808"}) {
            assertThatThrownBy(() -> update(Map.of("temperature", invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> update(Map.of("unknown", "1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** @param revisions 待验证序号映射 @return 有效来源及属性身份的最小信封 */
    private static DeviceRealtimeUpdate update(Map<String, String> revisions) {
        return new DeviceRealtimeUpdate(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), "1.0.0", Instant.parse("2026-09-12T00:00:00Z"),
                7, "reported-contract", Map.of("temperature", "23.5"), Map.of("temperature", "NUMBER"), revisions);
    }
}
