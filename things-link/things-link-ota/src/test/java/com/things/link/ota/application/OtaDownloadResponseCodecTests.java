package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 真实发布签名、最大来源集合和秘密地址边界。 */
class OtaDownloadResponseCodecTests {
    /** 默认HTTPS合同。 */
    private final OtaDownloadResponseCodec codec = new OtaDownloadResponseCodec();

    /** 独立公开签名向量可往返，清单精确保留且默认字符串不泄漏地址。 */
    @Test void publishedGoldenRoundTrip() throws Exception {
        var response = response(URI.create("https://storage.example/object?signature=secret"), false);
        byte[] encoded = codec.encode(response);
        var restored = codec.decode(encoded);
        assertArrayEquals(response.manifest(), restored.manifest());
        assertArrayEquals(response.signature(), restored.signature());
        assertArrayEquals(encoded, codec.encode(restored));
        assertFalse(response.toString().contains("secret"));
        byte[] copy = restored.manifest();
        copy[0] = 0;
        assertArrayEquals(response.manifest(), restored.manifest());
        assertEquals("tc/v1/p/d/down/ota/download/response", OtaDownloadResponseCodec.topic("p", "d"));
        assertThrows(IllegalArgumentException.class, () -> OtaDownloadResponseCodec.topic("p/other", "d"));
    }

    /** 最大256个来源ID嵌套传递，不把manifest扩成超限单字符串。 */
    @Test void largestSourceSetWithRealSignature() throws Exception {
        var response = response(URI.create("https://storage.example/object"), true);
        assertTrue(response.manifest().length > 10000);
        byte[] encoded = codec.encode(response);
        assertTrue(encoded.length < 65536);
        assertArrayEquals(response.manifest(), codec.decode(encoded).manifest());
    }

    /** 签名、外层身份、闭集和总字节任何一项不符均拒绝。 */
    @Test void rejectsTamperedSignatureAndOuterContract() throws Exception {
        String encoded = new String(codec.encode(response(URI.create("https://storage.example/object"), false)), StandardCharsets.UTF_8);
        for (String bad : new String[] {
                encoded.replace("\"attemptNo\":1", "\"attemptNo\":0"),
                encoded.replace("\"attemptNo\":1", "\"attemptNo\":1,\"attemptNo\":1"),
                encoded.replaceFirst("\\{", "{\"unexpected\":true,"),
                encoded.replace("\"signatureBase64\":\"", "\"signatureBase64\":\"A"),
                encoded.replace("tc-ota-download-response/v1", "tc-ota-download-response/v2"),
                encoded + " ".repeat(65537) }) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(bad.getBytes(StandardCharsets.UTF_8)));
            assertEquals("OTA下载响应合同不合法", failure.getMessage());
        }
    }

    /** URL不能暗含凭据、fragment或任意明文站点；开发例外仅精确回环。 */
    @Test void rejectsUnsafeAddresses() throws Exception {
        for (String url : new String[] {"http://localhost/a", "https://user:pass@storage.example/a",
                "https://storage.example/a#fragment", "https:/a", "https://storage.example/" + "a".repeat(8192)}) {
            var input = response(URI.create(url), false);
            assertThrows(IllegalArgumentException.class, () -> codec.encode(input));
        }
        var local = new OtaDownloadResponseCodec(true);
        assertTrue(local.encode(response(URI.create("http://127.0.0.1/a"), false)).length > 0);
        var nonlocal = response(URI.create("http://localhost.attacker.example/a"), false);
        assertThrows(IllegalArgumentException.class, () -> local.encode(nonlocal));
    }

    /** 普通例用仓库独立公开黄金向量；大集合仅测试内生成瞬时签名私钥，不落盘。 */
    private static OtaDownloadResponseCodec.Response response(URI url, boolean large) throws Exception {
        var json = new OtaCanonicalJson();
        byte[] manifest = new OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        byte[] signature = HexFormat.of().parseHex(new String(resource("manifest-v1.signature.hex"), StandardCharsets.UTF_8).trim());
        byte[] spki = HexFormat.of().parseHex(new String(resource("manifest-v1.spki.hex"), StandardCharsets.UTF_8).trim());
        if (large) {
            var fields = new LinkedHashMap<>(json.parseObject(manifest));
            var ids = new ArrayList<String>();
            for (int i = 1; i <= 256; i++) ids.add(new UUID(0, i).toString());
            fields.put("allowedSourceThingModelVersionIds", ids);
            fields.put("firmwareVersion", "固".repeat(128));
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            spki = key.getPublic().getEncoded();
            fields.put("signingKeyFingerprint", OtaTrustBundleCodec.sha256(spki));
            manifest = new OtaManifestCodec().canonicalize(json.writeObject(fields));
            var signing = Signature.getInstance("Ed25519");
            signing.initSign(key.getPrivate());
            signing.update("thingslink-ota-release-manifest-v1\u0000".getBytes(StandardCharsets.UTF_8));
            signing.update(manifest);
            signature = signing.sign();
        }
        var fields = json.parseObject(manifest);
        return new OtaDownloadResponseCodec.Response("tc-ota-download-response/v1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.fromString((String) fields.get("firmwareId")), 1,
                OtaTrustBundleCodec.sha256(manifest), manifest, signature, spki, OtaSignatureProfile.ED25519_V1,
                OtaTrustBundleCodec.sha256(spki), url, Instant.parse("2026-09-12T12:00:00Z"));
    }

    /** 有界测试资源读取，资源中只有公开清单、公钥和签名。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaDownloadResponseCodecTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("测试公开向量缺失");
            return input.readAllBytes();
        }
    }
}
