package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.infrastructure.security.AesGcmPushTokenCipher;
import com.things.link.enduser.infrastructure.security.PushTokenEncryptionProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-GCM 密文、AAD、轮换与失败关闭的纯逻辑合同（G2-A2a L0/L1）。 */
class AesGcmPushTokenCipherTests {

    /** 旧 key，固定 32 字节。 */
    private static final String OLD_KEY = key("old-key");
    /** 当前 key，固定 32 字节。 */
    private static final String NEW_KEY = key("new-key");

    /** 同一事实可以解密，且每次写入 nonce/密文不同。 */
    @Test
    void encryptsWithRandomNonceAndDecryptsOriginalValue() {
        PushTokenCipher cipher = cipher("new", Map.of("new", NEW_KEY));
        AppPushToken token = token(AppPushToken.Provider.HUAWEI);

        EncryptedPushToken first = cipher.encrypt(token, "vendor-token-secret");
        EncryptedPushToken second = cipher.encrypt(token, "vendor-token-secret");

        assertThat(first.keyId()).isEqualTo("new");
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.cipherText()).isNotEqualTo(second.cipherText());
        assertThat(new String(first.cipherText(), StandardCharsets.UTF_8))
                .doesNotContain("vendor-token-secret");
        assertThat(cipher.decrypt(token, first)).isEqualTo("vendor-token-secret");
    }

    /** 记录 ID、租户、用户、安装实例或 provider 被换到另一行时 GCM 认证必须失败。 */
    @Test
    void aadRejectsEnvelopeMovedToAnotherFact() {
        PushTokenCipher cipher = cipher("new", Map.of("new", NEW_KEY));
        AppPushToken source = token(AppPushToken.Provider.XIAOMI);
        EncryptedPushToken encrypted = cipher.encrypt(source, "secret");
        AppPushToken tampered = new AppPushToken(
                source.id(), source.tenantId(), source.appUserId(), UUID.randomUUID(),
                source.provider(), source.status(), source.createdAt(), source.updatedAt(), null);

        assertThatThrownBy(() -> cipher.decrypt(tampered, encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PUSH token 加解密或认证失败");
    }

    /** 切换 active key 后仍可按行内 keyId 读取旧信封。 */
    @Test
    void rotationKeepsOldKeyReadable() {
        AppPushToken token = token(AppPushToken.Provider.OPPO);
        EncryptedPushToken oldEnvelope = cipher("old", Map.of("old", OLD_KEY)).encrypt(token, "old-secret");
        PushTokenCipher rotated = cipher("new", Map.of("old", OLD_KEY, "new", NEW_KEY));

        assertThat(rotated.decrypt(token, oldEnvelope)).isEqualTo("old-secret");
        assertThat(rotated.encrypt(token, "new-secret").keyId()).isEqualTo("new");
    }

    /** 未知旧 key、非法 Base64 与非 256 bit key 都不能降级启动或解密。 */
    @Test
    void invalidOrMissingKeysFailClosed() {
        AppPushToken token = token(AppPushToken.Provider.VIVO);
        EncryptedPushToken oldEnvelope = cipher("old", Map.of("old", OLD_KEY)).encrypt(token, "secret");

        assertThatThrownBy(() -> cipher("new", Map.of("new", NEW_KEY)).decrypt(token, oldEnvelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PUSH token 引用未知密钥版本");
        assertThatThrownBy(() -> cipher("bad", Map.of("bad", "not-base64")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 Base64");
        assertThatThrownBy(() -> cipher("short", Map.of("short", Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须恰为 32 字节");
    }

    /** 信封数组访问器返回副本，调用方不能静默改写认证材料。 */
    @Test
    void encryptedEnvelopeDefensivelyCopiesArrays() {
        byte[] cipherText = {1, 2, 3};
        byte[] nonce = new byte[12];
        EncryptedPushToken encrypted = new EncryptedPushToken(cipherText, nonce, "key");

        cipherText[0] = 9;
        nonce[0] = 9;
        encrypted.cipherText()[1] = 9;
        encrypted.nonce()[1] = 9;

        assertThat(encrypted.cipherText()).containsExactly(1, 2, 3);
        assertThat(encrypted.nonce()).containsOnly(0);
    }

    /** 创建冻结身份元数据。 */
    private static AppPushToken token(AppPushToken.Provider provider) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new AppPushToken(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                provider, AppPushToken.Status.ACTIVE, now, now, null);
    }

    /** 创建被测加密端口。 */
    private static PushTokenCipher cipher(String active, Map<String, String> keys) {
        return AesGcmPushTokenCipher.from(new PushTokenEncryptionProperties(active, keys));
    }

    /** 生成内容可识别但严格 32 字节的测试 key。 */
    private static String key(String prefix) {
        byte[] bytes = new byte[32];
        byte[] marker = prefix.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 0, marker.length);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
