package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.application.PushTokenCipher;
import com.things.link.enduser.domain.AppPushToken;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** AES-256-GCM PUSH token 信封加密器（ADR 0051）。 */
public final class AesGcmPushTokenCipher implements PushTokenCipher {

    /** GCM 推荐的 96 bit nonce。 */
    private static final int NONCE_BYTES = 12;
    /** 128 bit 认证标签，拒绝弱化完整性。 */
    private static final int TAG_BITS = 128;
    /** AAD 格式版本，未来变更编码时必须显式升级。 */
    private static final byte AAD_VERSION = 1;
    /** active 写入密钥 ID。 */
    private final String activeKeyId;
    /** 已校验的版本化 AES 密钥环。 */
    private final Map<String, SecretKeySpec> keys;
    /** 每行 nonce 的密码学随机源。 */
    private final SecureRandom random;

    private AesGcmPushTokenCipher(String activeKeyId, Map<String, SecretKeySpec> keys, SecureRandom random) {
        this.activeKeyId = activeKeyId;
        this.keys = Map.copyOf(keys);
        this.random = random;
    }

    /**
     * 从配置构造并一次性校验全部 256 bit 密钥。
     *
     * @param properties 密钥环配置
     * @return 可用加密器
     */
    public static AesGcmPushTokenCipher from(PushTokenEncryptionProperties properties) {
        if (properties.activeKeyId() == null || properties.activeKeyId().isBlank()
                || properties.keys() == null || properties.keys().isEmpty()) {
            throw new IllegalStateException("缺少 PUSH token active key 或密钥环");
        }
        Map<String, SecretKeySpec> decoded = new LinkedHashMap<>();
        properties.keys().forEach((id, encoded) -> {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("PUSH token 密钥不是合法 Base64: " + id, ex);
            }
            if (raw.length != 32) {
                throw new IllegalStateException("PUSH token 密钥必须恰为 32 字节: " + id);
            }
            decoded.put(id, new SecretKeySpec(raw, "AES"));
        });
        if (!decoded.containsKey(properties.activeKeyId())) {
            throw new IllegalStateException("PUSH token active key 不在密钥环中");
        }
        return new AesGcmPushTokenCipher(properties.activeKeyId(), decoded, new SecureRandom());
    }

    @Override
    public EncryptedPushToken encrypt(AppPushToken token, String plainToken) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new EncryptedPushToken(crypt(Cipher.ENCRYPT_MODE, token,
                plainToken.getBytes(StandardCharsets.UTF_8), nonce, activeKeyId), nonce, activeKeyId);
    }

    @Override
    public String decrypt(AppPushToken token, EncryptedPushToken encrypted) {
        if (encrypted.nonce().length != NONCE_BYTES) {
            throw new IllegalStateException("PUSH token nonce 长度非法");
        }
        return new String(crypt(Cipher.DECRYPT_MODE, token, encrypted.cipherText(),
                encrypted.nonce(), encrypted.keyId()), StandardCharsets.UTF_8);
    }

    /** 执行 GCM 并绑定稳定事实 AAD；任何异常统一 fail-closed。 */
    private byte[] crypt(int mode, AppPushToken token, byte[] input, byte[] nonce, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("PUSH token 引用未知密钥版本");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(token));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PUSH token 加解密或认证失败", ex);
        }
    }

    /** 使用定长 UUID 与长度前缀 provider，避免字符串拼接歧义。 */
    private byte[] aad(AppPushToken token) {
        byte[] provider = token.provider().name().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16 * 4 + 4 + provider.length);
        buffer.put(AAD_VERSION);
        putUuid(buffer, token.id());
        putUuid(buffer, token.tenantId());
        putUuid(buffer, token.appUserId());
        putUuid(buffer, token.installationId());
        buffer.putInt(provider.length).put(provider);
        return buffer.array();
    }

    /** 写入 UUID 两个 long，固定网络字节序。 */
    private void putUuid(ByteBuffer buffer, java.util.UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }
}
