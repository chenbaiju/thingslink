package com.things.link.ota.api;

import com.things.link.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 发布信封严格原文验证，解析通过不代表上传/签名/发布资格。 */
class OtaPublicationRequestParserTests {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private final OtaPublicationRequestParser parser = new OtaPublicationRequestParser();
    @Test void canonicalizesManifestAndProtectsItsBytes() {
        var request = parser.parse(body("0", ID, "{\"z\":2,\"a\":1}"));
        assertThat(request.uploadSessionId().toString()).isEqualTo(ID);
        assertThat(new String(request.manifest(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1,\"z\":2}");
        request.manifest()[0] = 0;
        assertThat(request.manifest()[0]).isEqualTo((byte) '{');
    }
    @Test void rejectsNoncanonicalRevisionAndAbbreviatedUploadIdentity() {
        for (String revision : new String[] { "-1", "01", "9223372036854775808" }) {
            assertThatThrownBy(() -> parser.parse(body(revision, ID, "{}"))).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> parser.parse(body("0", "1-1-1-1-1", "{}"))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsDuplicateUnknownNullAndWrongTypeFields() {
        for (String input : new String[] {
                new String(body("0", ID, "{\"a\":1,\"a\":2}"), StandardCharsets.UTF_8),
                new String(body("0", ID, "[]"), StandardCharsets.UTF_8),
                new String(body("0", ID, "{}"), StandardCharsets.UTF_8).replace("\"expectedRevision\":\"0\"", "\"expectedRevision\":0"),
                new String(body("0", ID, "{}"), StandardCharsets.UTF_8).replace("\"manifest\":", "\"signer\":\"local\",\"manifest\":"),
                "{}", "null" }) {
            assertThatThrownBy(() -> parser.parse(input.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void enforcesSharedByteAndNestedObjectBudget() {
        assertThatThrownBy(() -> parser.parse(body("0", ID, "{\"a\":\"" + "a".repeat(65536) + "\"}")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse(body("0", ID, "{\"a\":".repeat(33) + "0" + "}".repeat(33))))
                .isInstanceOf(BusinessException.class);
    }
    private static byte[] body(String revision, String upload, String manifest) {
        return ("{\"expectedRevision\":\"" + revision + "\",\"uploadSessionId\":\"" + upload
                + "\",\"manifest\":" + manifest + "}").getBytes(StandardCharsets.UTF_8);
    }
}
