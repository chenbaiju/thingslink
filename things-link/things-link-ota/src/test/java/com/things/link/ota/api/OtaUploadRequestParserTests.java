package com.things.link.ota.api;

import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 严格JSON与长度/摘要/修订边界，不接受存储地址或类型强转。 */
class OtaUploadRequestParserTests {
    private final OtaUploadRequestParser parser = new OtaUploadRequestParser();
    private static final String SHA = "a".repeat(64);
    @Test void acceptsOnlyBoundedIntegralLengthAndLowercaseDigest() {
        assertThat(parser.create(bytes("{\"expectedLength\":67108864,\"expectedSha256\":\"" + SHA + "\"}"))
                .expectedLength()).isEqualTo(67108864);
        for (String length : new String[] { "0", "67108865", "-1", "1.0", "1e0", "\"1\"", "null", "true" }) {
            assertThatThrownBy(() -> parser.create(bytes("{\"expectedLength\":" + length
                    + ",\"expectedSha256\":\"" + SHA + "\"}"))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void rejectsUnknownDuplicateMissingAndMalformedFields() {
        for (String json : new String[] {
                "{}", "{\"expectedLength\":1,\"expectedLength\":1,\"expectedSha256\":\"" + SHA + "\"}",
                "{\"expectedLength\":1,\"expectedSha256\":\"" + SHA + "\",\"bucket\":\"test\"}",
                "{\"expectedLength\":1,\"expectedSha256\":\"" + SHA.toUpperCase() + "\"}",
                "{\"expectedLength\":1,\"expectedSha256\":null}" }) {
            assertThatThrownBy(() -> parser.create(bytes(json))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void revisionRequiresCanonicalNonnegativeLongText() {
        assertThat(parser.cancel(bytes("{\"expectedRevision\":\"0\"}")).expectedRevision()).isEqualTo("0");
        for (String value : new String[] { "0", "null", "\"01\"", "\"-1\"", "\"9223372036854775808\"" }) {
            assertThatThrownBy(() -> parser.cancel(bytes("{\"expectedRevision\":" + value + "}")))
                    .isInstanceOf(BusinessException.class);
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
