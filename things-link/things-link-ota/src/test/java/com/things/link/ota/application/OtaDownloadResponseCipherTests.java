package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 密文轮换、关联身份和失败关闭，不加载任何生产密钥。 */
class OtaDownloadResponseCipherTests {
    /** 仅本测试的第一版本固定材料。 */
    private static final String FIRST = Base64.getEncoder().encodeToString(new byte[32]);
    /** 仅本测试的第二版本独立材料。 */
    private static final String SECOND = Base64.getEncoder().encodeToString("test-only-second-key-32-byte-dat".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** 当前版本加密，旧版本仍可解密；每次随机nonce且数组防御复制。 */
    @Test void rotationAndIndependentNonces() {
        var binding = binding(1);
        byte[] clear = {1, 2, 3};
        var original = new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\"}", "v1");
        var first = original.encrypt(binding, clear);
        var second = original.encrypt(binding, clear);
        assertNotEquals(Base64.getEncoder().encodeToString(first.nonce()), Base64.getEncoder().encodeToString(second.nonce()));
        var rotated = new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\",\"v2\":\"" + SECOND + "\"}", "v2");
        assertArrayEquals(clear, rotated.decrypt(binding, first));
        assertNotEquals(first.keyVersion(), rotated.encrypt(binding, clear).keyVersion());
        byte[] tamperedCopy = first.ciphertext();
        tamperedCopy[0] ^= 1;
        assertArrayEquals(clear, rotated.decrypt(binding, first));
        assertFalse(first.toString().contains(Base64.getEncoder().encodeToString(first.ciphertext())));
    }

    /** 改正文、nonce、身份、期限或密钥版本都拒绝且不保留异常链。 */
    @Test void rejectsTamperingAndWrongBinding() {
        var cipher = new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\"}", "v1");
        var binding = binding(1);
        var envelope = cipher.encrypt(binding, new byte[] {1});
        byte[] modified = envelope.ciphertext();
        modified[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(binding,
                new OtaDownloadResponseCipher.Envelope("v1", envelope.nonce(), modified)));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(binding,
                new OtaDownloadResponseCipher.Envelope("missing", envelope.nonce(), envelope.ciphertext())));
        byte[] nonce = envelope.nonce();
        nonce[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(binding,
                new OtaDownloadResponseCipher.Envelope("v1", nonce, envelope.ciphertext())));
        var aliases = new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\",\"alias\":\"" + FIRST + "\"}", "v1");
        assertThrows(IllegalArgumentException.class, () -> aliases.decrypt(binding,
                new OtaDownloadResponseCipher.Envelope("alias", envelope.nonce(), envelope.ciphertext())));
        var changed = new OtaDownloadResponseCipher.Binding(binding.tenantId(), binding.projectId(), binding.deviceId(),
                2, binding.requestId(), binding.jobId(), binding.authorizationId(), binding.attemptNo(), binding.manifestSha256(),
                binding.expiresAt(), binding.contractVersion());
        var failure = assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(changed, envelope));
        assertNull(failure.getCause());
        var later = new OtaDownloadResponseCipher.Binding(binding.tenantId(), binding.projectId(), binding.deviceId(),
                1, binding.requestId(), binding.jobId(), binding.authorizationId(), binding.attemptNo(), binding.manifestSha256(),
                binding.expiresAt().plusSeconds(1), binding.contractVersion());
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(later, envelope));
    }

    /** 无默认密钥，部分配置或非256bit配置不能启动；正文上下界独立执行。 */
    @Test void configurationAndPayloadBounds() {
        var absent = new AesGcmOtaDownloadResponseCipher("", "");
        assertFalse(absent.configured());
        assertThrows(IllegalArgumentException.class, () -> absent.encrypt(binding(1), new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new AesGcmOtaDownloadResponseCipher("{}", "v1"));
        assertThrows(IllegalArgumentException.class, () -> new AesGcmOtaDownloadResponseCipher("{\"v1\":\"AA==\"}", "v1"));
        assertThrows(IllegalArgumentException.class, () -> new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\"}", "v2"));
        var cipher = new AesGcmOtaDownloadResponseCipher("{\"v1\":\"" + FIRST + "\"}", "v1");
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(binding(1), new byte[65537]));
        var binding = binding(1);
        assertArrayEquals(new byte[65536], cipher.decrypt(binding, cipher.encrypt(binding, new byte[65536])));
    }

    /** 每个测试创建完整非秘密范围。 */
    private static OtaDownloadResponseCipher.Binding binding(long generation) {
        return new OtaDownloadResponseCipher.Binding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), generation,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "a".repeat(64),
                Instant.parse("2026-09-12T00:00:00Z"), "tc-ota-download-response/v1");
    }
}
