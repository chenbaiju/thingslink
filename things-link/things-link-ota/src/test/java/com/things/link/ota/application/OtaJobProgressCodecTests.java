package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 公开固定向量、词法闭集和语义裁决边界，不伪造设备真实验签证据。 */
class OtaJobProgressCodecTests {
    /** 被测词法合同。 */
    private final OtaJobProgressCodec codec = new OtaJobProgressCodec();

    /** 黄金字节和独立摘要一致，规范化与防御复制稳定。 */
    @Test void goldenCanonicalAndDefensiveCopies() throws Exception {
        byte[] golden = resource("job-progress-v1.json");
        var decoded = codec.decode(golden);
        assertArrayEquals(golden, decoded.canonical());
        assertEquals(new String(resource("job-progress-v1.sha256"), StandardCharsets.UTF_8).trim(), decoded.sha256());
        assertEquals(9007199254740991L, decoded.value().evidence().securityVersion());
        assertEquals(67108864, decoded.value().evidence().artifactSize());
        byte[] output = decoded.canonical();
        output[0] = 0;
        assertArrayEquals(golden, decoded.canonical());
        String spaced = "  " + new String(golden, StandardCharsets.UTF_8).replace(",", ", ") + "  ";
        assertEquals(decoded.sha256(), codec.decode(bytes(spaced)).sha256());
    }

    /** 16KiB计算原始字节，字段上限不可依靠规范化去空白绕过。 */
    @Test void enforcesInputByteBudget() throws Exception {
        String golden = new String(resource("job-progress-v1.json"), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> codec.decode(bytes(golden + " ".repeat(16384 - bytes(golden).length))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes(golden + " ".repeat(16385 - bytes(golden).length))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[0]));
    }

    /** 字段缺失/重复/扩展、浮点、未知阶段及非法枚举全部是词法拒绝。 */
    @Test void rejectsMalformedClosedFieldsAndRanges() throws Exception {
        String golden = new String(resource("job-progress-v1.json"), StandardCharsets.UTF_8);
        for (String bad : new String[] {
                golden.replace("\"progressSeq\":1", "\"progressSeq\":0"),
                golden.replace("\"progressSeq\":1", "\"progressSeq\":9007199254740992"),
                golden.replace("\"attemptNo\":1", "\"attemptNo\":2147483648"),
                golden.replace("\"attemptNo\":1", "\"attemptNo\":1.0"),
                golden.replace("\"attemptNo\":1", "\"attemptNo\":1,\"attemptNo\":1"),
                golden.replace("\"artifactSize\":67108864", "\"artifactSize\":67108865"),
                golden.replace("\"bootVerified\":false", "\"bootVerified\":\"false\""),
                golden.replace("\"bootVerified\":false,", ""),
                golden.replace("\"evidence\":{", "\"evidence\":{\"extra\":1,"),
                golden.replace("\"evidence\":{", "\"deviceId\":\"spoof\",\"evidence\":{"),
                golden.replace("VERIFYING", "SUCCEEDED"),
                golden.replace("\"activeSlot\":\"A\"", "\"activeSlot\":\"C\""),
                golden.replace("NOT_STARTED", "FAILED"),
                golden.replace("PG_JSONB_TEXT_V1_SHA256", "SHA256"),
                golden.replace("\"trustBundleVersion\":1", "\"trustBundleVersion\":null") }) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes(bad)));
        }
    }

    /** 真实安全不一致不得提前当坏JSON丢掉，交事务服务记录与恢复裁决。 */
    @Test void preservesSemanticContradictionsForSafetyDecision() throws Exception {
        String golden = new String(resource("job-progress-v1.json"), StandardCharsets.UTF_8);
        var result = codec.decode(bytes(golden.replace("VERIFYING", "HEALTH_CHECKING")
                .replace("\"sourceSlot\":\"A\"", "\"sourceSlot\":\"SINGLE\"")));
        assertEquals("NOT_STARTED", result.value().evidence().verification());
        assertEquals("SINGLE", result.value().evidence().sourceSlot());
        assertEquals("HEALTH_CHECKING", result.value().stage());
    }

    /** 测试向量只含公开身份与声明，不包含私钥。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaJobProgressCodecTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("进度黄金向量缺失");
            return input.readAllBytes();
        }
    }
    /** 真实UTF8字节。 */
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
