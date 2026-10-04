package com.things.link.telemetry.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 凭据字段整字段移除，包含嵌套对象/数组与已知HTTP驼峰别名；不猜测业务字段。 */
class MessageLogRedactorTests {
    private final ObjectMapper json = new ObjectMapper();
    private final MessageLogRedactor redactor = new MessageLogRedactor(json);
    @Test void stripsKnownCamelCaseCredentialsRecursivelyWhilePreservingBusinessFields() {
        String input = """
                {"value":42,"accessToken":"canary","nested":{"RefreshToken":"canary",
                "clientSecret":"canary","brokerKey":"canary","callbackKey":"canary"},
                "rows":[{"apiKey":"canary","setCookie":"canary","xTcDeviceSecret":"canary",
                "tcCredential":"canary","access_token":"canary","other_secret":"canary","ok":7}],
                "tokenCount":9,"businessTokenBalance":10}
                """;
        var result = json.readTree(redactor.redact(input));
        assertThat(result.toString()).doesNotContain("canary");
        assertThat(result.path("nested").isEmpty()).isTrue();
        assertThat(result.path("rows").get(0).size()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("ok").asInt()).isEqualTo(7);
        assertThat(result.path("tokenCount").asInt()).isEqualTo(9);
        assertThat(result.path("businessTokenBalance").asInt()).isEqualTo(10);
        assertThat(result.path("value").asInt()).isEqualTo(42);
    }
    @Test void unchangedBusinessSummaryRemainsValidAndIdempotent() {
        String summary="{\"value\":42,\"nested\":[{\"temperature\":20}]}";
        String once=redactor.redact(summary);
        assertThat(json.readTree(once)).isEqualTo(json.readTree(summary));
        assertThat(redactor.redact(once)).isEqualTo(once);
    }
}
