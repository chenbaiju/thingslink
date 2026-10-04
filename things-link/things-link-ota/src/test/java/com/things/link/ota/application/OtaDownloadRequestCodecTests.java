package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 独立规范向量与下载申请字段边界。 */
class OtaDownloadRequestCodecTests {
    /** 人工固定字段顺序的黄金JSON。 */
    private static final String GOLDEN = """
            {"attemptNo":1,"contractVersion":"tc-ota-download-request/v1","jobId":"22222222-2222-4222-8222-222222222222","manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","requestId":"11111111-1111-4111-8111-111111111111"}""";
    /** 被测合同。 */
    private final OtaDownloadRequestCodec codec = new OtaDownloadRequestCodec();

    /** 字段顺序与空白不改变摘要，访问副本不可更改内部合同。 */
    @Test void canonicalGoldenAndDefensiveCopy() {
        var decoded = codec.decode(bytes(GOLDEN));
        assertArrayEquals(bytes(GOLDEN), decoded.canonical());
        assertEquals("84b0fa2d1291862a5a8b55db0ef4888fed20fae775f804b0964b2b757b3a39aa", decoded.sha256());
        var spaced = codec.decode(bytes("  " + GOLDEN.replace(",", ", ") + "  "));
        assertEquals(decoded.sha256(), spaced.sha256());
        byte[] copy = decoded.canonical();
        copy[0] = 0;
        assertArrayEquals(bytes(GOLDEN), decoded.canonical());
    }

    /** 正整数边界、真实字节上限与闭集语法都不能放宽。 */
    @Test void rejectsMalformedAndOutOfBounds() {
        for (String changed : new String[] {
                GOLDEN.replace("\"attemptNo\":1", "\"attemptNo\":0"),
                GOLDEN.replace("\"attemptNo\":1", "\"attemptNo\":2147483648"),
                GOLDEN.replace("\"attemptNo\":1", "\"attemptNo\":1.0"),
                GOLDEN.replace("\"attemptNo\":1", "\"attemptNo\":null"),
                GOLDEN.replace("\"attemptNo\":1", "\"attemptNo\":1,\"attemptNo\":1"),
                GOLDEN.replace("{", "{\"url\":\"secret\","),
                GOLDEN.replace("tc-ota-download-request/v1", "tc-ota-download-request/v2"),
                GOLDEN.replace("a".repeat(64), "A".repeat(64)),
                GOLDEN.replace("11111111-1111-4111-8111-111111111111", "1-1-1-1-1") }) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes(changed)));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes(GOLDEN + " ".repeat(2049))));
        assertEquals(2147483647, codec.decode(bytes(GOLDEN.replace("\"attemptNo\":1",
                "\"attemptNo\":2147483647"))).value().attemptNo());
        assertDoesNotThrow(() -> codec.decode(bytes(GOLDEN + " ".repeat(2048 - bytes(GOLDEN).length))));
    }

    /** 统一使用真实UTF8字节。 */
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
