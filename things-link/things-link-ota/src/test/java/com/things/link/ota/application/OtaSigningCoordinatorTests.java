package com.things.link.ota.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 外部服务仅作合同替身；独立公开签名证明回执必须匹配准确请求，不认领生产接线。 */
class OtaSigningCoordinatorTests {
    /** 固定请求身份用于检查错配，不作为供应商幂等能力声明。 */
    private static final UUID REQUEST_ID = UUID.fromString("018f0000-0000-7000-8000-000000000009");
    /** 公开测试键不可变版本，只用于端口合同。 */
    private static final String KEY_VERSION = "test:release-key/1";

    /** 验证一次调用、域字节、响应与双读可信版本，同时防止数组后续被调用方修改。 */
    @Test
    void signsOnceAndReturnsDefensiveVerifiedResult() throws Exception {
        byte[] manifest = resource("manifest-v1.json");
        OtaSigningTrustSource.Binding binding = binding();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        OtaReleaseSigner.Response response = response();
        OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> {
            calls.incrementAndGet();
            assertThat(request.requestId()).isEqualTo(REQUEST_ID);
            assertThat(request.trustDomain()).isEqualTo(binding.trustDomain());
            assertThat(request.profile()).isEqualTo(binding.profile());
            assertThat(request.fingerprint()).isEqualTo(binding.fingerprint());
            assertThat(request.keyVersion()).isEqualTo(KEY_VERSION);
            byte[] prefix = "thingslink-ota-release-manifest-v1\0".getBytes(StandardCharsets.UTF_8);
            byte[] expected = new byte[prefix.length + manifest.length];
            System.arraycopy(prefix, 0, expected, 0, prefix.length);
            System.arraycopy(manifest, 0, expected, prefix.length, manifest.length);
            assertThat(request.signingInput()).isEqualTo(expected);
            request.signingInput()[0] = 0;
            assertThat(request.signingInput()).isEqualTo(expected);
            return response;
        }, domain -> {
            assertThat(domain).isEqualTo(binding.trustDomain());
            reads.incrementAndGet();
            return binding;
        });
        OtaSigningCoordinator.Result result = coordinator.sign(REQUEST_ID, manifest);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(reads.get()).isEqualTo(2);
        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.binding()).isEqualTo(binding);
        assertThat(result.receipt()).isEqualTo("test:receipt/1");
        assertThat(result.canonicalManifest()).isEqualTo(manifest);
        assertThat(result.spki()).isEqualTo(response.spki());
        assertThat(result.signature()).isEqualTo(response.signature());
        result.canonicalManifest()[0] = 0;
        result.spki()[0] = 0;
        result.signature()[0] = 0;
        assertThat(result.canonicalManifest()).isEqualTo(manifest);
        assertThat(result.spki()).isEqualTo(response.spki());
        assertThat(result.signature()).isEqualTo(response.signature());
    }

    /** 不合法manifest和缺请求身份必须在外部服务及可信源调用之前拒绝。 */
    @Test
    void rejectsInvalidManifestBeforeSideEffects() throws Exception {
        OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> {
            throw new AssertionError("signer must not run");
        }, domain -> { throw new AssertionError("trust source must not run"); });
        failure(coordinator, REQUEST_ID, "{}".getBytes(StandardCharsets.UTF_8),
                OtaSigningCoordinator.Reason.INVALID_MANIFEST);
        failure(coordinator, null, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.INVALID_MANIFEST);
        failure(coordinator, REQUEST_ID, null, OtaSigningCoordinator.Reason.INVALID_MANIFEST);
    }

    /** 可信绑定必须精确匹配域、Profile、指纹和有效的不可变键版本。 */
    @Test
    void rejectsMissingOrIncompatibleTrustBeforeSigning() throws Exception {
        OtaSigningTrustSource.Binding good = binding();
        for (OtaSigningTrustSource.Binding bad : new OtaSigningTrustSource.Binding[] {
                new OtaSigningTrustSource.Binding("other", KEY_VERSION, good.profile(), good.fingerprint(), 1),
                new OtaSigningTrustSource.Binding(good.trustDomain(), KEY_VERSION,
                        OtaSignatureProfile.ES256_P1363_V1, good.fingerprint(), 1),
                new OtaSigningTrustSource.Binding(good.trustDomain(), KEY_VERSION, good.profile(), "0".repeat(64), 1),
                new OtaSigningTrustSource.Binding(good.trustDomain(), "mutable key", good.profile(), good.fingerprint(), 1),
                new OtaSigningTrustSource.Binding(good.trustDomain(), KEY_VERSION, good.profile(), good.fingerprint(), 0)
        }) {
            OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> {
                throw new AssertionError("signer must not run");
            }, domain -> bad);
            failure(coordinator, REQUEST_ID, resource("manifest-v1.json"),
                    bad.revision() == 0 || bad.keyVersion().contains(" ")
                            ? OtaSigningCoordinator.Reason.TRUST_UNAVAILABLE : OtaSigningCoordinator.Reason.TRUST_MISMATCH);
        }
        OtaSigningCoordinator absent = new OtaSigningCoordinator(request -> {
            throw new AssertionError("signer must not run");
        }, domain -> null);
        failure(absent, REQUEST_ID, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.TRUST_UNAVAILABLE);
    }

    /** 故障不重试、不透传供应商异常消息；不可把结果未知当作已签名成功。 */
    @Test
    void sanitizesDependencyFailuresWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OtaSigningTrustSource.Binding binding = binding();
        OtaSigningCoordinator unavailable = new OtaSigningCoordinator(request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("credential=DO_NOT_LEAK");
        }, domain -> binding);
        failure(unavailable, REQUEST_ID, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.SIGNER_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
        OtaSigningCoordinator trustFailure = new OtaSigningCoordinator(request -> {
            throw new AssertionError("signer must not run");
        }, domain -> { throw new IllegalStateException("credential=DO_NOT_LEAK"); });
        failure(trustFailure, REQUEST_ID, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.TRUST_UNAVAILABLE);
    }

    /** 回执身份、公开密钥和签名各自独立核验；空回执不能推进VERIFYING。 */
    @Test
    void rejectsMismatchedMalformedAndTamperedResponses() throws Exception {
        OtaReleaseSigner.Response good = response();
        byte[] tampered = good.signature();
        tampered[0] ^= 1;
        for (OtaReleaseSigner.Response bad : new OtaReleaseSigner.Response[] {
                null,
                new OtaReleaseSigner.Response(UUID.randomUUID(), KEY_VERSION, good.profile(), good.spki(), good.signature(), good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, "test:key/2", good.profile(), good.spki(), good.signature(), good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, OtaSignatureProfile.ES256_P1363_V1,
                        good.spki(), good.signature(), good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), new byte[44], good.signature(), good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), good.spki(), tampered, good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), good.spki(), null, good.receipt()),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), good.spki(), good.signature(), ""),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), good.spki(), good.signature(), "a".repeat(257)),
                new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, good.profile(), good.spki(), good.signature(), "id?secret=value")
        }) {
            OtaSigningTrustSource.Binding binding = binding();
            OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> bad, domain -> binding);
            failure(coordinator, REQUEST_ID, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.INVALID_RESPONSE);
        }
    }

    /** 即使服务返回有效历史签名，改动合法manifest字段仍必须失败。 */
    @Test
    void rejectsValidSignatureForDifferentManifest() throws Exception {
        OtaReleaseSigner.Response response = response();
        OtaSigningTrustSource.Binding binding = binding();
        OtaCanonicalJson json = new OtaCanonicalJson();
        Map<String, Object> changed = new LinkedHashMap<>(json.parseObject(resource("manifest-v1.json")));
        changed.put("firmwareVersion", "next");
        failure(new OtaSigningCoordinator(request -> response, domain -> binding), REQUEST_ID,
                json.writeObject(changed), OtaSigningCoordinator.Reason.INVALID_RESPONSE);
    }

    /** 签名期间轮换、撤销或修订变化必须拒绝；双读不冒充后续READY事务的原子检查。 */
    @Test
    void rejectsTrustChangedDuringSigning() throws Exception {
        OtaSigningTrustSource.Binding original = binding();
        OtaReleaseSigner.Response response = response();
        AtomicReference<OtaSigningTrustSource.Binding> active = new AtomicReference<>(original);
        OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> {
            active.set(new OtaSigningTrustSource.Binding(original.trustDomain(), original.keyVersion(),
                    original.profile(), original.fingerprint(), original.revision() + 1));
            return response;
        }, domain -> active.get());
        failure(coordinator, REQUEST_ID, resource("manifest-v1.json"), OtaSigningCoordinator.Reason.TRUST_CHANGED);
    }

    /** 服务边界不能吞掉JVM级错误并伪装为可重试业务失败。 */
    @Test
    void doesNotSwallowErrors() throws Exception {
        OtaSigningTrustSource.Binding binding = binding();
        byte[] manifest = resource("manifest-v1.json");
        OtaSigningCoordinator coordinator = new OtaSigningCoordinator(request -> {
            throw new AssertionError("fatal test sentinel");
        }, domain -> binding);
        assertThatThrownBy(() -> coordinator.sign(REQUEST_ID, manifest)).isInstanceOf(AssertionError.class);
    }

    /** 端口记录必须同时隔离构造参数与访问器，防止异步适配器修改已核验事实。 */
    @Test
    void defensivelyCopiesPortArrays() throws Exception {
        byte[] spki = resource("manifest-v1.json");
        byte[] signature = new byte[64];
        OtaReleaseSigner.Response response = new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION,
                OtaSignatureProfile.ED25519_V1, spki, signature, "test:receipt/1");
        byte before = spki[0];
        spki[0] = 0;
        signature[0] = 1;
        assertThat(response.spki()[0]).isEqualTo(before);
        assertThat(response.signature()[0]).isZero();
        OtaReleaseSigner.Request request = new OtaReleaseSigner.Request(REQUEST_ID, "test", KEY_VERSION,
                OtaSignatureProfile.ED25519_V1, "a".repeat(64), signature);
        signature[0] = 2;
        assertThat(request.signingInput()[0]).isEqualTo((byte) 1);
    }

    /** 固定分类且无原始cause，避免调用者日志泄露供应商正文。 */
    private static void failure(OtaSigningCoordinator coordinator, UUID requestId, byte[] input,
                                OtaSigningCoordinator.Reason reason) {
        assertThatThrownBy(() -> coordinator.sign(requestId, input))
                .isInstanceOfSatisfying(OtaSigningCoordinator.Failure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.getMessage()).doesNotContain("DO_NOT_LEAK");
                    assertThat(failure.getCause()).isNull();
                });
    }

    /** 模拟已由可信配置证明ACTIVE的绑定，仅供端口测试。 */
    private static OtaSigningTrustSource.Binding binding() throws IOException {
        Map<String, Object> manifest = new OtaCanonicalJson().parseObject(resource("manifest-v1.json"));
        return new OtaSigningTrustSource.Binding((String) manifest.get("trustDomain"), KEY_VERSION,
                OtaSignatureProfile.ED25519_V1, (String) manifest.get("signingKeyFingerprint"), 1);
    }

    /** 供应商替身仅重放独立公开向量，不生成私钥或本地生产签名。 */
    private static OtaReleaseSigner.Response response() throws IOException {
        return new OtaReleaseSigner.Response(REQUEST_ID, KEY_VERSION, OtaSignatureProfile.ED25519_V1,
                hex("manifest-v1.spki.hex"), hex("manifest-v1.signature.hex"), "test:receipt/1");
    }

    /** 固定公钥与签名的十六进制资源。 */
    private static byte[] hex(String name) throws IOException {
        return HexFormat.of().parseHex(new String(resource(name), StandardCharsets.UTF_8).strip());
    }

    /** 公开资源缺失直接报告测试夹具错误。 */
    private static byte[] resource(String name) throws IOException {
        try (var stream = OtaSigningCoordinatorTests.class.getResourceAsStream("/ota/" + name)) {
            if (stream == null) {
                throw new IOException("Missing public golden vector");
            }
            return stream.readAllBytes();
        }
    }
}
