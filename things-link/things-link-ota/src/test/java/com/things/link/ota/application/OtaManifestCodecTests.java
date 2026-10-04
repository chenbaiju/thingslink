package com.things.link.ota.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 完整manifest固定公开黄金向量和字段资格；不依赖生产私钥或外部服务。 */
class OtaManifestCodecTests {
    /** 完整合同及签名联验入口。 */
    private final OtaManifestCodec codec = new OtaManifestCodec();
    /** 仅用于构造语法有效但字段不合格的反例。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** Python独立序列化ASCII字段顺序及UTF8文本，OpenSSL独立签名；私钥已删除。 */
    @Test
    void acceptsIndependentlySignedCompleteManifest() throws Exception {
        byte[] canonical = resource("manifest-v1.json");
        assertThat(codec.canonicalize(canonical)).isEqualTo(canonical);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)))
                .isEqualTo(new String(resource("manifest-v1.sha256"), StandardCharsets.UTF_8).strip());
        assertThat(verify(canonical)).isTrue();
        Map<String, Object> reversed = new LinkedHashMap<>();
        new ArrayList<>(json.parseObject(canonical).entrySet()).reversed()
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        String reversedJson = reversed.entrySet().stream().map(entry -> {
            String pair = new String(json.writeObject(Map.of(entry.getKey(), entry.getValue())), StandardCharsets.UTF_8);
            return pair.substring(1, pair.length() - 1);
        }).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        assertThat(codec.canonicalize(reversedJson.getBytes(StandardCharsets.UTF_8))).isEqualTo(canonical);
        String spaced = "\n  " + new String(canonical, StandardCharsets.UTF_8) + "\n";
        assertThat(verify(spaced.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    /** 合法字段更改同样改变签名字节，外部可信指纹不可由manifest自声明替换。 */
    @Test
    void rejectsTamperingAndUntrustedFingerprint() throws Exception {
        byte[] changed = mutate(map -> map.put("firmwareVersion", "固件1.0.1"));
        assertThat(codec.canonicalize(changed)).isNotEmpty();
        assertThat(verify(changed)).isFalse();
        assertThat(codec.verifySignature(resource("manifest-v1.json"), hex("manifest-v1.spki.hex"),
                "0".repeat(64), hex("manifest-v1.signature.hex"))).isFalse();
        assertThat(codec.verifySignature(null, null, null, null)).isFalse();
    }

    /** 每个冻结字段必填，派发URL与未知根/嵌套字段不得进入签名对象。 */
    @Test
    void rejectsMissingAndUnknownFields() throws Exception {
        for (String field : valid().keySet()) {
            reject(map -> map.remove(field));
        }
        reject(map -> map.put("downloadUrl", "https://example.invalid/firmware"));
        reject(map -> nested(map, "hardware").put("futureBoardPolicy", true));
        reject(map -> nested(map, "delta").put("baselineSha256", "c".repeat(64)));
        reject(map -> nested(map, "requirements").put("arbitraryAlgorithm", "unsafe"));
    }

    /** 数量、标识和策略精确验证，不把展示版本当安全版本。 */
    @Test
    void rejectsInvalidFieldValues() throws Exception {
        reject(map -> map.put("artifactSize", 0L));
        reject(map -> map.put("minimumTrustBundleVersion", 0L));
        reject(map -> map.put("firmwareId", "1-1-1-1-1"));
        reject(map -> map.put("productKey", "../product"));
        reject(map -> map.put("trustDomain", " domain"));
        reject(map -> map.put("artifactSha256", "A".repeat(64)));
        reject(map -> map.put("thingModelSchemaDigestAlgorithm", "JCS_SHA256"));
        reject(map -> map.put("signatureProfile", "Ed25519"));
        reject(map -> map.put("compression", "GZIP"));
        reject(map -> nested(map, "delta").put("mode", "BSDIFF"));
        reject(map -> nested(map, "hardware").put("boardRevisionMin", 4L));
        reject(map -> nested(map, "requirements").put("requiresProtectedSecurityCounter", false));
        reject(map -> nested(map, "requirements").put("requiresAbSlots", "true"));
        reject(map -> nested(map, "requirements").put("profile", "UNKNOWN"));
        for (String invalid : List.of("1.0", "01.0.0", "1.0.0-rc1", "2147483648.0.0")) {
            reject(map -> map.put("bootloaderMinimumVersion", invalid));
        }
    }

    /** 精确来源集合必须非空、唯一、排序且有界，禁止通配资格。 */
    @Test
    void rejectsAmbiguousSourceVersions() throws Exception {
        String first = "018f0000-0000-7000-8000-000000000004";
        String second = "018f0000-0000-7000-8000-000000000005";
        for (List<String> invalid : List.of(List.<String>of(), List.of(first, first), List.of(second, first), List.of("*"))) {
            reject(map -> map.put("allowedSourceThingModelVersionIds", invalid));
        }
        byte[] sorted = mutate(map -> map.put("allowedSourceThingModelVersionIds", List.of(first, second)));
        assertThat(codec.canonicalize(sorted)).isEqualTo(sorted);
    }

    /** 安全版本可为零，签名算法的另一个冻结Profile只改变本身字段资格。 */
    @Test
    void acceptsSafeBoundaryFieldsWithoutClaimingSignature() throws Exception {
        byte[] input = mutate(map -> {
            map.put("securityVersion", 0L);
            map.put("artifactSize", 9_007_199_254_740_991L);
            map.put("signatureProfile", "TC_OTA_ES256_P1363_V1");
            nested(map, "requirements").put("requiresAbSlots", false);
        });
        assertThat(codec.canonicalize(input)).isEqualTo(input);
        assertThat(verify(input)).isFalse();
    }

    /** 同一反例同时验证合同报错与验签失败关闭。 */
    private void reject(Consumer<Map<String, Object>> mutation) throws Exception {
        byte[] input = mutate(mutation);
        assertThatThrownBy(() -> codec.canonicalize(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid OTA manifest field:");
        assertThat(verify(input)).isFalse();
    }

    /** 从独立黄金正文变更一个字段，隔离失败原因。 */
    private byte[] mutate(Consumer<Map<String, Object>> mutation) throws Exception {
        Map<String, Object> value = valid();
        mutation.accept(value);
        return json.writeObject(value);
    }

    /** 每个反例使用独立可变根副本。 */
    private Map<String, Object> valid() throws IOException {
        return new LinkedHashMap<>(json.parseObject(resource("manifest-v1.json")));
    }

    /** 解析器保证嵌套对象键类型，复制后修改不污染黄金正文。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> value, String name) {
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) value.get(name));
        value.put(name, result);
        return result;
    }

    /** 外部可信指纹来自公开SPKI，不能信任manifest自声明。 */
    private boolean verify(byte[] input) throws Exception {
        byte[] spki = hex("manifest-v1.spki.hex");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        return codec.verifySignature(input, spki, fingerprint, hex("manifest-v1.signature.hex"));
    }

    /** 黄金十六进制文件只包含公开数据。 */
    private static byte[] hex(String name) throws IOException {
        return HexFormat.of().parseHex(new String(resource(name), StandardCharsets.UTF_8).strip());
    }

    /** 从测试资源读取固定向量，缺失立即失败。 */
    private static byte[] resource(String name) throws IOException {
        try (var stream = OtaManifestCodecTests.class.getResourceAsStream("/ota/" + name)) {
            if (stream == null) {
                throw new IOException("Missing public golden vector");
            }
            return stream.readAllBytes();
        }
    }
}
