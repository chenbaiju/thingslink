package com.things.link.ota.api;

import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 信任信封严格语法、字节预算和签名编码，不把成功解析当作根验签。 */
class OtaTrustRequestParserTests {
    private final OtaTrustRequestParser parser = new OtaTrustRequestParser();
    private static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[64]);
    @Test void canonicalizesNestedBundleAndDefensivelyCopiesBytes() {
        var result = parser.parse(body("0", "{\"z\":2,\"a\":1}", SIGNATURE));
        assertThat(new String(result.bundle(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1,\"z\":2}");
        result.bundle()[0] = 0;
        result.signature()[0] = 1;
        assertThat(result.bundle()[0]).isEqualTo((byte) '{');
        assertThat(result.signature()[0]).isZero();
    }
    @Test void rejectsDuplicateNestedKeysUnknownFieldsAndNonobjectBundle() {
        for (String json : new String[] {
                new String(body("0", "{\"a\":1,\"a\":2}", SIGNATURE), StandardCharsets.UTF_8),
                new String(body("0", "[]", SIGNATURE), StandardCharsets.UTF_8),
                "{\"expectedRevision\":0,\"bundle\":{},\"signature\":\"" + SIGNATURE + "\"}",
                new String(body("0", "{}", SIGNATURE), StandardCharsets.UTF_8).replace("\"bundle\":", "\"unknown\":0,\"bundle\":"),
                "{}" }) {
            assertThatThrownBy(() -> parser.parse(json.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void rejectsNoncanonicalSignatureLengthAndRevision() {
        for (String signature : new String[] { SIGNATURE.substring(0, 86), SIGNATURE + "\n",
                Base64.getEncoder().encodeToString(new byte[63]), "!".repeat(88) }) {
            assertThatThrownBy(() -> parser.parse(body("0", "{}", signature))).isInstanceOf(BusinessException.class);
        }
        for (String revision : new String[] { "01", "-1", "9223372036854775808" }) {
            assertThatThrownBy(() -> parser.parse(body(revision, "{}", SIGNATURE))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void enforcesSharedEnvelopeByteAndDepthBudgets() {
        assertThatThrownBy(() -> parser.parse(body("0", "{\"a\":\"" + "a".repeat(65536) + "\"}", SIGNATURE)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse(body("0", "{\"a\":".repeat(33) + "0" + "}".repeat(33), SIGNATURE)))
                .isInstanceOf(BusinessException.class);
    }
    private static byte[] body(String revision, String bundle, String signature) {
        return ("{\"expectedRevision\":\"" + revision + "\",\"bundle\":" + bundle + ",\"signature\":\"" + signature + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
