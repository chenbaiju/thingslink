package com.things.link.ota.api;

import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生命周期正文必须保留精确二字段，拒绝未知操作者或客户端时间。 */
class OtaFirmwareLifecycleRequestParserTests {
    /** 受测严格解码器。 */
    private final OtaFirmwareLifecycleRequestParser parser = new OtaFirmwareLifecycleRequestParser();
    /** 原因和long修订不由JSON数字或trim隐式改写。 */
    @Test
    void preservesExactStringFieldsForServiceValidation() {
        var request = parser.parse("{\"reason\":\"明确原因😀\",\"expectedRevision\":\"9223372036854775807\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(request.expectedRevision()).isEqualTo("9223372036854775807");
        assertThat(request.reason()).isEqualTo("明确原因😀");
    }
    /** 缺失/null/数值/重复/未知以及孤立代理JSON均不能宽松接受。 */
    @Test
    void rejectsMalformedClosedEnvelope() {
        for (String body : new String[] {"{}", "{\"expectedRevision\":2,\"reason\":\"x\"}",
                "{\"expectedRevision\":\"2\",\"reason\":null}",
                "{\"expectedRevision\":\"2\",\"reason\":\"x\",\"reason\":\"y\"}",
                "{\"expectedRevision\":\"2\",\"reason\":\"x\",\"actorId\":\"attacker\"}",
                "{\"expectedRevision\":\"2\",\"reason\":\"\\ud800\"}"}) {
            assertThatThrownBy(() -> parser.parse(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
        }
    }
}
