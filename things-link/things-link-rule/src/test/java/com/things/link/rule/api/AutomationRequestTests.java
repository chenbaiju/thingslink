package com.things.link.rule.api;
import com.things.link.rule.api.dto.request.CreateAutomationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
/** 实际请求解码证明嵌套数值不会经double舍入，额外身份字段拒绝。 */
class AutomationRequestTests {
    @Test void preservesPrecisionAndRejectsExtraIdentity(){
        var json=JsonMapper.builder().build();
        String body="""
            {"name":"test","triggerType":"PROPERTY_REPORTED","triggerConfig":{"deviceId":"00000000-0000-0000-0000-000000000001"},
            "conditions":[],"actions":[{"nodeType":"device-property-set-action","config":{"properties":{"value":0.123456789012345678901}}}]}
            """;
        var request=json.readValue(body,CreateAutomationRequest.class);
        assertThat(request.actions().getFirst().config().path("properties").path("value").decimalValue()).isEqualByComparingTo("0.123456789012345678901");
        assertThatThrownBy(()->json.readValue(body.replace("\"name\":", "\"responsibleAccountId\":\"fake\",\"name\":"),CreateAutomationRequest.class)).isInstanceOf(RuntimeException.class);
    }
}
