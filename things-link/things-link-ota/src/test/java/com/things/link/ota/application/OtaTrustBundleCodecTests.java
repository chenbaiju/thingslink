package com.things.link.ota.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 独立OpenSSL公开黄金向量验证根签名域及有界闭集，私钥不进入测试运行时。 */
class OtaTrustBundleCodecTests {
    /** 固定范围仅用于公开密码学向量。 */
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    /** 固定项目仅用于公开密码学向量。 */
    private static final UUID PROJECT = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    /** 受测完整根签名入口。 */
    private final OtaTrustBundleCodec codec = new OtaTrustBundleCodec();
    /** 测试只用生产JCS生成畸形结构，成功黄金字节由Python独立生成。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 两种Profile独立固定字节成功，发布域不能验证同一根签名。 */
    @Test
    void acceptsIndependentGoldenVectorsAndSeparatesReleaseDomain() throws Exception {
        for (String name : List.of("ed25519", "es256")) {
            byte[] body = resource(name + "-bundle.json");
            byte[] signature = signature(name);
            var root = root(name);
            var result = codec.verify(body, signature, root);
            assertThat(result.canonical()).containsExactly(body);
            assertThat(result.sha256()).isEqualTo(new String(resource(name + "-bundle.sha256"), StandardCharsets.US_ASCII).trim());
            assertThat(result.bundleVersion()).isEqualTo(1);
            assertThat(result.keys()).hasSize(1);
            assertThat(result.keys().getFirst().state()).isEqualTo("ACTIVE");
            assertThat(new OtaReleaseSignatureVerifier().verify(root.rootProfile(), root.rootSpki(),
                    root.rootFingerprint(), body, signature)).isFalse();
            signature[0] ^= 1;
            assertThatThrownBy(() -> codec.verify(body, signature, root)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 合法根签名仍不能授权离线根兼任在线发布键，避免以坏签名掩盖角色隔离检查。 */
    @Test
    void rejectsRootAsReleaseEvenWhenBundleSignatureIsValid() throws Exception {
        byte[] body = resource("root-as-release-bundle.json");
        byte[] signature = signature("root-as-release");
        var root = root("root-as-release");
        assertThat(new OtaReleaseSignatureVerifier().verifyBundle(root.rootProfile(), root.rootSpki(),
                root.rootFingerprint(), body, signature)).isTrue();
        assertThatThrownBy(() -> codec.verify(body, signature, root)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 返回包及公开密钥不能被调用方修改，列表不允许插入。 */
    @Test
    void returnsDefensiveBytesAndImmutableKeys() throws Exception {
        var result = codec.verify(resource("ed25519-bundle.json"), signature("ed25519"), root("ed25519"));
        byte[] canonical = result.canonical();
        byte[] spki = result.keys().getFirst().spki();
        canonical[0] = 0;
        spki[0] = 0;
        assertThat(result.canonical()[0]).isEqualTo((byte) '{');
        assertThat(result.keys().getFirst().spki()[0]).isEqualTo((byte) 0x30);
        assertThatThrownBy(() -> result.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 完整字段闭集、类型、时刻上限、状态和标准Base64均不能宽松接受。 */
    @Test
    void rejectsInvalidFieldsTimeProfilesAndEncoding() throws Exception {
        for (Consumer<Map<String, Object>> mutation : List.<Consumer<Map<String, Object>>>of(
                key -> key.put("unknown", "value"), key -> key.remove("state"),
                key -> key.put("state", "DISABLED"), key -> key.put("state", "PREPARED"),
                key -> key.put("signatureProfile", "Ed25519"), key -> key.put("notBefore", "0"),
                key -> key.put("notAfter", 0L), key -> key.put("notAfter", 253_402_300_800L),
                key -> key.put("spki", ((String) key.get("spki")).replace("=", "")),
                key -> key.put("fingerprint", "0".repeat(64)))) {
            assertThatThrownBy(() -> codec.canonicalize(mutateKey(mutation))).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, Object> fields = fields();
        fields.put("bundleVersion", 0L);
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        fields.put("bundleVersion", 1L);
        fields.put("extra", true);
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.canonicalize("{\"keys\":[],\"keys\":[]}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.canonicalize(new byte[65_537])).isInstanceOf(IllegalArgumentException.class);
    }

    /** 重复版本、重复指纹、超过64项和空集合都拒绝。 */
    @Test
    void rejectsDuplicateKeyIdentitiesAndCollectionBudgets() throws Exception {
        Map<String, Object> fields = fields();
        Map<String, Object> key = key(fields);
        Map<String, Object> duplicateFingerprint = new LinkedHashMap<>(key);
        duplicateFingerprint.put("keyVersion", "different");
        duplicateFingerprint.put("state", "PREPARED");
        fields.put("keys", List.of(key, duplicateFingerprint));
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        fields.put("keys", List.of(key, key));
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        fields.put("keys", List.of());
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        fields.put("keys", new ArrayList<>(java.util.Collections.nCopies(65, key)));
        assertThatThrownBy(() -> codec.canonicalize(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 入库前即拒绝Ed25519低阶键，不能等到将来验固件才发现信任包内键无资格。 */
    @Test
    void rejectsDegeneratePublishedKeyEvenWithMatchingFingerprint() throws Exception {
        byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100" + "01" + "00".repeat(31));
        assertThatThrownBy(() -> codec.canonicalize(mutateKey(key -> {
            key.put("spki", Base64.getEncoder().encodeToString(spki));
            key.put("fingerprint", OtaTrustBundleCodec.sha256(spki));
        }))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 正确DER长度和指纹也不能让P-256非曲线点获得公开键资格。 */
    @Test
    void rejectsOffCurveP256KeyWithMatchingFingerprint() throws Exception {
        byte[] spki = HexFormat.of().parseHex("3059301306072a8648ce3d020106082a8648ce3d03010703420004"
                + "00".repeat(64));
        assertThatThrownBy(() -> codec.canonicalize(mutateKey(key -> {
            key.put("signatureProfile", "TC_OTA_ES256_P1363_V1");
            key.put("spki", Base64.getEncoder().encodeToString(spki));
            key.put("fingerprint", OtaTrustBundleCodec.sha256(spki));
        }))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 同一ECDSA数学签名的high-S形式必须拒绝，不做静默归一化。 */
    @Test
    void rejectsHighSEcdsaRootSignature() throws Exception {
        byte[] signature = signature("es256");
        BigInteger order = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signature, 32, 64));
        byte[] high = order.subtract(s).toByteArray();
        System.arraycopy(high, high.length - 32, signature, 32, 32);
        byte[] bundle = resource("es256-bundle.json");
        var root = root("es256");
        assertThatThrownBy(() -> codec.verify(bundle, signature, root)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 修改单个键字段以构造精确拒绝反例。 */
    private byte[] mutateKey(Consumer<Map<String, Object>> mutation) throws Exception {
        Map<String, Object> fields = fields();
        Map<String, Object> key = key(fields);
        mutation.accept(key);
        fields.put("keys", List.of(key));
        return json.writeObject(fields);
    }
    /** 读取固定公开成功包并复制最外层用于反例。 */
    private Map<String, Object> fields() throws Exception {
        return new LinkedHashMap<>(json.parseObject(resource("ed25519-bundle.json")));
    }
    /** 拷贝第一公开键供独立变更。 */
    private static Map<String, Object> key(Map<String, Object> fields) {
        return new LinkedHashMap<>(OtaTrustBundleCodec.object(((List<?>) fields.get("keys")).getFirst()));
    }
    /** 从受控配置解析公开测试根，不能从bundle反向构造根。 */
    private static OtaTrustAnchors.Anchor root(String name) throws Exception {
        return new OtaTrustAnchors(new String(resource(name + "-anchors.json"), StandardCharsets.UTF_8))
                .require(TENANT, PROJECT, "test.domain");
    }
    /** 读取公开签名原始字节。 */
    private static byte[] signature(String name) throws Exception {
        return HexFormat.of().parseHex(new String(resource(name + "-signature.hex"), StandardCharsets.US_ASCII).trim());
    }
    /** 仓库只提供公开向量，资源缺失立即失败。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaTrustBundleCodecTests.class.getResourceAsStream("/ota/trust/" + name)) {
            if (input == null) throw new IllegalStateException("缺少公开测试向量");
            return input.readAllBytes();
        }
    }
}
