package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 固定独立规范向量及闭集能力边界，不能由基线声明推导实机通过。 */
class OtaTypeBaselineCodecTests {
    /** 被测编解码器。 */
    private final OtaTypeBaselineCodec codec = new OtaTypeBaselineCodec();
    /** 测试修改配置时仍走真实有界JSON。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** Python独立生成公开规范字节与摘要，Profile顺序不改变身份。 */
    @Test void canonicalGoldenAndDefensiveCopies() throws Exception {
        byte[] golden = resource("type-baseline-v1.json");
        var decoded = codec.decode(golden);
        assertArrayEquals(golden, decoded.canonical());
        assertEquals(new String(resource("type-baseline-v1.sha256"), StandardCharsets.US_ASCII).trim(), decoded.sha256());
        var reversed = fields();
        reversed.put("signatureProfiles", List.of("TC_OTA_ES256_P1363_V1", "TC_OTA_ED25519_V1"));
        assertEquals(decoded.sha256(), codec.decode(json.writeObject(reversed)).sha256());
        byte[] copy = decoded.canonical(); copy[0] = 0;
        assertArrayEquals(golden, decoded.canonical());
        assertThrows(UnsupportedOperationException.class, () -> decoded.value().signatureProfiles().clear());
    }
    /** 数值元组比较及硬件序号必须满足顺序，不能以字典序验收。 */
    @Test void rejectsInvalidVersionAndHardwareRanges() throws Exception {
        assertEquals("1.10.0", codec.decode(resource("type-baseline-v1.json")).value().bootloader().maximumVersion());
        for (String minimum : List.of("1.11.0", "01.2.0", "2147483648.0.0", "1.0", "v1.0.0")) {
            rejects("bootloader", Map.of("minimumVersion", minimum, "maximumVersion", "1.10.0"));
        }
        rejects("hardware", Map.of("model", "board.v1", "boardRevisionMin", 4L, "boardRevisionMax", 3L));
    }
    /** 缺能力可记录但不伪造支持；范围和真实布尔不接受隐式转换。 */
    @Test void validatesCapabilitiesWithoutInventingQualification() throws Exception {
        var value = fields();
        value.put("supportsAbSlots", false); value.put("supportsResumeDownload", false);
        value.put("supportsRangeDownload", false); value.put("protectedSecurityCounterBits", 0L);
        value.put("availableRamBytes", 0L); value.put("availableFlashBytes", 0L);
        assertFalse(codec.decode(json.writeObject(value)).value().supportsAbSlots());
        rejects("supportsRangeDownload", false);
        rejects("supportsAbSlots", "true"); rejects("maximumArtifactBytes", 67_108_865L);
        rejects("maximumArtifactBytes", 0L); rejects("protectedSecurityCounterBits", 54L);
        rejects("compressionAlgorithms", List.of("GZIP")); rejects("deltaModes", List.of());
        rejects("signatureProfiles", List.of("TC_OTA_ED25519_V1", "TC_OTA_ED25519_V1"));
        rejects("signatureProfiles", List.of()); rejects("signatureProfiles", List.of("Ed25519"));
    }
    /** 规范标识、来源索引以及未知字段均不能被静默清理。 */
    @Test void rejectsClosedShapeAndInvalidText() throws Exception {
        rejects("unknown", "value"); rejects("rootFingerprint", "A".repeat(64));
        rejects("tenantId", "00000000-0000-0000-0000-1");
        rejects("productKey", " test_product");
        for (String text : List.of("", " x", "x\u00a0", "x\ny", "x".repeat(257))) rejects("evidenceReference", text);
        var value = fields(); value.put("evidenceReference", "😀".repeat(256));
        assertEquals(256, codec.decode(json.writeObject(value)).value().evidenceReference().codePointCount(0, 512));
        String raw = new String(resource("type-baseline-v1.json"), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(raw.replaceFirst("\\{", "{\"tenantId\":null,").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[65_537]));
    }
    /** 验证单字段反例通过真实JSON路径拒绝。 */
    private void rejects(String field, Object value) throws Exception {
        var data = fields(); data.put(field, value);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.writeObject(data)), field);
    }
    /** 独立公开向量的可变测试副本。 */
    private Map<String, Object> fields() throws Exception { return new LinkedHashMap<>(json.parseObject(resource("type-baseline-v1.json"))); }
    /** 读取仅公开配置的固定资源。 */
    static byte[] resource(String name) throws Exception {
        try (var stream = OtaTypeBaselineCodecTests.class.getResourceAsStream("/ota/baseline/" + name)) {
            assertNotNull(stream); return stream.readAllBytes();
        }
    }
}
