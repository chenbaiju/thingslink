package com.things.link.assistant.infrastructure.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ModelRequestFingerprintTests {
    final JsonMapper json=JsonMapper.builder().build();
    byte[] evidence() { return json.writeValueAsBytes(new InternalAnalysisRequestEncoderTests().input()); }
    @Test void exactUserTextAndFixedOptionsProduceOnlyDigestForProductionCaller() throws Exception {
        byte[] input=evidence(), copy=input.clone(), body=ModelRequestFingerprint.encode(input);
        var request=json.readTree(body);
        assertThat(request.propertyNames()).isEqualTo(Set.of("model","thinking","stream","max_tokens","response_format","messages"));
        assertThat(request.get("model").asString()).isEqualTo("deepseek-flash");
        assertThat(request.get("thinking").get("type").asString()).isEqualTo("disabled");
        assertThat(request.get("stream").asBoolean()).isFalse();
        assertThat(request.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(request.get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(request.get("messages")).hasSize(2);
        assertThat(request.get("messages").get(1).get("content").asString()).isEqualTo(new String(input,StandardCharsets.UTF_8));
        assertThat(ModelRequestFingerprint.fingerprint(input)).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(input).isEqualTo(copy);
        byte[] changed=new String(input,StandardCharsets.UTF_8).replace("0.12345678901234567890123456789","0.12345678901234567890123456788").getBytes(StandardCharsets.UTF_8);
        assertThat(ModelRequestFingerprint.fingerprint(changed)).isNotEqualTo(ModelRequestFingerprint.fingerprint(input));
    }
    @Test void completeRequestBudgetCountsPromptAndEscapesRatherThanOnlyEvidence() {
        byte[] input=evidence();
        String text=new String(input,StandardCharsets.UTF_8);
        int padding=16384-ModelRequestFingerprint.encode(input).length;
        assertThat(ModelRequestFingerprint.encode((" ".repeat(padding)+text).getBytes(StandardCharsets.UTF_8))).hasSize(16384);
        rejects((" ".repeat(padding+1)+text).getBytes(StandardCharsets.UTF_8));
    }
    @Test void resourceReplacementMissingAndOversizeHaveNoFallbackOrPrivateCause() throws Exception {
        byte[] resource;
        try(var stream=ModelRequestFingerprint.class.getResourceAsStream("/com/things/link/assistant/model-request-contract.json")) {
            assertThat(stream).isNotNull();resource=stream.readAllBytes();
        }
        var contract=ModelRequestFingerprint.checkedContract(resource);
        assertThat(contract.keySet()).containsExactlyInAnyOrder("STATUS_SUMMARY","ALARM_EXPLANATION");
        assertThatThrownBy(()->contract.clear()).isInstanceOf(UnsupportedOperationException.class);
        byte[] changed=resource.clone();changed[0]='!';
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[16385],changed,Arrays.copyOf(resource,resource.length+1)})
            assertThatThrownBy(()->ModelRequestFingerprint.checkedContract(invalid))
                    .hasMessage("INVALID_MODEL_REQUEST_BINDING").hasNoCause();
    }
    @Test void badUtf8ClosedTemplateDuplicateAndTrailingPayloadAreRejectedWithoutEcho() {
        for(String input:new String[]{"null","[]","{}","{\"template\":true}","{\"template\":\"private-other\"}",
                "{\"template\":\"STATUS_SUMMARY\",\"template\":\"ALARM_EXPLANATION\"}","{\"template\":\"STATUS_SUMMARY\"}{}"})
            rejects(input.getBytes(StandardCharsets.UTF_8));
        rejects(null);rejects(new byte[0]);rejects(new byte[]{(byte)0xff});rejects(new byte[16385]);
    }
    void rejects(byte[] value) {
        assertThatThrownBy(()->ModelRequestFingerprint.fingerprint(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MODEL_REQUEST_BINDING").hasNoCause();
    }
}
