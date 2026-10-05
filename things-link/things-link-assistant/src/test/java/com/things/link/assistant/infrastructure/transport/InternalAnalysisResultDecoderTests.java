package com.things.link.assistant.infrastructure.transport;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InternalAnalysisResultDecoderTests {
    final JsonMapper json = JsonMapper.builder().build();
    final UUID id = UUID.fromString("019c1234-5678-7890-8123-456789abcdef");
    final String digest = "a".repeat(64);
    ObjectNode body() {
        return (ObjectNode) json.readTree("""
            {"version":"agent-internal-analysis-result-v2","callId":"019c1234-5678-7890-8123-456789abcdef",
             "configurationRevision":2,"inputSha256":"%s","requestSha256":"%s","counterSha256":"%s",
             "executionCandidateSha256":"%s","model":"deepseek-flash","promptVersion":"thingslink-agent-single-analysis-v1",
             "qualification":"OFFLINE_UNQUALIFIED","status":"VALIDATED","rejectionCode":null,
             "providerFingerprintSha256":"%s","usage":{"promptTokens":100,"completionTokens":10,
             "totalTokens":110,"cacheHitTokens":0,"cacheMissTokens":100},
             "content":{"summary":"private-summary","findings":[{"kind":"FACT","statement":"private-statement",
             "evidenceIds":["e-device"]}],"limitations":["private-limitation"]}}
            """.formatted(digest, digest, digest, digest, digest));
    }
    InternalAnalysisResultDecoder.Result decode(byte[] raw) {
        return InternalAnalysisResultDecoder.decode(raw, id, 2, digest, digest, digest, digest, Set.of("e-device"));
    }
    void rejects(Consumer<ObjectNode> mutate) {
        var body = body(); mutate.accept(body);
        rejects(json.writeValueAsBytes(body));
    }
    void rejects(byte[] raw) {
        assertThatThrownBy(() -> decode(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_INTERNAL_ANALYSIS_RESULT").hasNoCause();
    }
    @Test void validatesBindingUsageReferencesAndHidesAllContentInDefaultPrinting() {
        var result = decode(json.writeValueAsBytes(body()));
        assertThat(result.qualification()).isEqualTo("OFFLINE_UNQUALIFIED");
        assertThat(result.usage().totalTokens()).isEqualTo(110);
        assertThat(result.content().findings().getFirst().evidenceIds()).containsExactly("e-device");
        assertThat(result.toString()).doesNotContain("private", digest);
        assertThat(result.content().toString()).doesNotContain("private");
        assertThat(result.content().findings().getFirst().toString()).doesNotContain("private");
        assertThatThrownBy(() -> result.content().findings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.content().findings().getFirst().evidenceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void unknownUsageIsNotZeroConsumption() {
        var value = body(); value.put("status", "REJECTED").put("rejectionCode", "INVALID_MODEL_RESPONSE");
        value.putNull("content").putNull("usage").putNull("providerFingerprintSha256");
        var result = decode(json.writeValueAsBytes(value));
        assertThat(result.usage()).isNull(); assertThat(result.content()).isNull();
        assertThat(result.status()).isEqualTo("REJECTED");
    }
    @Test void oldVersionMissingOrDifferentCandidateCannotFallBackToCounterOnly() {
        rejects(value -> value.put("version", "agent-internal-analysis-result-v1"));
        rejects(value -> value.remove("executionCandidateSha256"));
        rejects(value -> value.put("executionCandidateSha256", "b".repeat(64)));
        rejects(value -> value.putNull("executionCandidateSha256"));
        for (String expected : new String[]{null, "", "bad", "b".repeat(64)}) {
            assertThatThrownBy(() -> InternalAnalysisResultDecoder.decode(json.writeValueAsBytes(body()),
                    id, 2, digest, digest, expected, digest, Set.of("e-device")))
                    .hasMessage("INVALID_INTERNAL_ANALYSIS_RESULT").hasNoCause();
        }
        assertThat(decode(json.writeValueAsBytes(body())).executionCandidateSha256()).isEqualTo(digest);
    }
    @Test void contentRejectionKeepsVerifiedUsage() {
        var value = body(); value.put("status", "REJECTED").put("rejectionCode", "OUTPUT_TRUNCATED").putNull("content");
        assertThat(decode(json.writeValueAsBytes(value)).usage().completionTokens()).isEqualTo(10);
    }
    @ParameterizedTest @ValueSource(strings={"version","callId","inputSha256","requestSha256","counterSha256","executionCandidateSha256","model","promptVersion","qualification","status"})
    void identityAndQualificationAreNotCallerSelectable(String key) { rejects(value -> value.put(key, "forged")); }
    @Test void closedWireRejectsExtraMissingDuplicateTrailingAndOversizeInput() {
        rejects(value -> value.put("credential", "private-key")); rejects(value -> value.remove("status"));
        var bytes = json.writeValueAsString(body());
        rejects(bytes.replace("\"status\":\"VALIDATED\"", "\"status\":\"REJECTED\",\"status\":\"VALIDATED\"").getBytes(StandardCharsets.UTF_8));
        rejects((bytes + "{}").getBytes(StandardCharsets.UTF_8)); rejects(new byte[24 * 1024 + 1]);
        rejects(new byte[]{(byte) 0xff}); rejects("[]".getBytes(StandardCharsets.UTF_8));
    }
    @Test void usageCannotBeCoercedExceededOverflowedOrInconsistent() {
        rejects(value -> ((ObjectNode) value.get("usage")).put("promptTokens", true));
        rejects(value -> ((ObjectNode) value.get("usage")).put("promptTokens", "100"));
        rejects(value -> ((ObjectNode) value.get("usage")).put("totalTokens", 109));
        rejects(value -> ((ObjectNode) value.get("usage")).put("cacheMissTokens", 99));
        rejects(value -> ((ObjectNode) value.get("usage")).put("completionTokens", 1025));
        rejects(value -> ((ObjectNode) value.get("usage")).put("cacheHitTokens", Long.MAX_VALUE));
        rejects(value -> value.put("configurationRevision", 2.0));
        rejects(value -> value.putNull("usage"));
    }
    @Test void referencesAndStatusRemainStrictEvenIfResponseClaimsValidation() {
        rejects(value -> ((ObjectNode) value.get("content").get("findings").get(0)).put("kind", "TOOL"));
        rejects(value -> ((ObjectNode) value.get("content").get("findings").get(0)).putArray("evidenceIds").add("foreign"));
        rejects(value -> value.put("status", "REJECTED").put("rejectionCode", "OUTPUT_TRUNCATED"));
        rejects(value -> value.put("status", "REJECTED").put("rejectionCode", "private-provider-error").putNull("content"));
        rejects(value -> value.putNull("providerFingerprintSha256"));
    }
    @Test void reviewedWireRequiresIndependentDigestAndCannotPromoteLegacy() {
        var value=body();value.put("version","agent-internal-analysis-result-v3")
            .put("qualification","REVIEWED_EXECUTION").put("releaseReviewSha256",digest);
        byte[] raw=json.writeValueAsBytes(value);
        rejects(raw);
        var decoded=InternalAnalysisResultDecoder.decode(raw,id,2,digest,digest,digest,digest,Set.of("e-device"),digest);
        assertThat(decoded.qualification()).isEqualTo("REVIEWED_EXECUTION");
        assertThat(decoded.releaseReviewSha256()).isEqualTo(digest);
        for(String expected:new String[]{"bad","b".repeat(64)})
            assertThatThrownBy(()->InternalAnalysisResultDecoder.decode(raw,id,2,digest,digest,digest,digest,Set.of("e-device"),expected))
                .hasMessage("INVALID_INTERNAL_ANALYSIS_RESULT").hasNoCause();
        assertThatThrownBy(()->InternalAnalysisResultDecoder.decode(json.writeValueAsBytes(body()),id,2,digest,digest,digest,digest,Set.of("e-device"),digest))
            .hasMessage("INVALID_INTERNAL_ANALYSIS_RESULT").hasNoCause();
    }
}
