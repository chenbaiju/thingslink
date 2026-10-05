package com.things.link.assistant.infrastructure.security;

import com.things.link.assistant.domain.ModelCredential;
import com.things.link.assistant.domain.ModelCredentialCipher;
import com.things.link.assistant.domain.ModelCredentialProtectionException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 独立 AES-256-GCM 主密钥环；缺配置允许平台启动但保存、启用均失败关闭。 */
@Component
@EnableConfigurationProperties(ModelCredentialEncryptionProperties.class)
public final class AesGcmModelCredentialCipher implements ModelCredentialCipher {
    private final String active;
    private final Map<String,SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();
    public AesGcmModelCredentialCipher(ModelCredentialEncryptionProperties properties) {
        active = properties.getActiveKeyId();
        var decoded = new HashMap<String,SecretKeySpec>();
        try {
            properties.getKeys().forEach((id,value) -> {
                if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw failure();
                byte[] raw = Base64.getDecoder().decode(value);
                try {
                    if (raw.length != 32) throw failure();
                    decoded.put(id, new SecretKeySpec(raw,"AES"));
                } finally { Arrays.fill(raw,(byte)0); }
            });
            if (!decoded.isEmpty() && !decoded.containsKey(active)) throw failure();
            if (decoded.isEmpty() && active != null && !active.isBlank()) throw failure();
        } catch (RuntimeException ignored) { throw failure(); }
        keys = Map.copyOf(decoded);
    }
    /** 沿用接口加密契约；生成随机 nonce，并在退出前清零临时明文字节。{@inheritDoc} */
    @Override public ModelCredential encrypt(ModelCredential c, String secret) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
        try {
            return new ModelCredential(c.id(),c.tenantId(),c.projectId(),c.revision(),c.credentialRevision(),false,
                crypt(Cipher.ENCRYPT_MODE,c,plain,nonce,active),nonce,active,c.updatedBy(),c.updatedAt());
        } finally { Arrays.fill(plain,(byte)0); }
    }
    /** 沿用接口验证契约；验证成功后立即清零解密结果，不向调用方交付。{@inheritDoc} */
    @Override public void verify(ModelCredential c) {
        byte[] plain = crypt(Cipher.DECRYPT_MODE,c,c.ciphertext(),c.nonce(),c.keyId());
        Arrays.fill(plain,(byte)0);
    }
    /** 沿用接口交付契约；回调成功或失败均在退出前清零明文字节。{@inheritDoc} */
    @Override public <T> T deliver(ModelCredential c, java.util.function.Function<byte[],T> callback) {
        byte[] plain = crypt(Cipher.DECRYPT_MODE,c,c.ciphertext(),c.nonce(),c.keyId());
        try { return callback.apply(plain); } finally { Arrays.fill(plain,(byte)0); }
    }
    /**
     * 执行认证加解密，将租户、项目、凭据标识、凭据版本、用途及主密钥标识绑定为附加认证数据。
     * @param mode 加密或解密模式
     * @param c 提供隔离身份及凭据版本的可信记录
     * @param input 本次明文或密文字节，由调用方负责明文清零
     * @param nonce 十二字节随机数，加密生成、解密取自持久记录
     * @param id 本次使用的主密钥标识，必须存在于配置密钥环
     * @return 认证加密后的密文或已验证身份绑定的明文字节
     * @throws ModelCredentialProtectionException 密钥、输入或身份认证失败，错误不含秘密
     */
    private byte[] crypt(int mode, ModelCredential c, byte[] input, byte[] nonce, String id) {
        if (id == null || !keys.containsKey(id) || input == null || nonce == null || nonce.length != 12) throw failure();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,keys.get(id),new GCMParameterSpec(128,nonce));
            byte[] purpose = ModelCredential.PURPOSE.getBytes(StandardCharsets.US_ASCII);
            byte[] keyId = id.getBytes(StandardCharsets.US_ASCII);
            var aad = ByteBuffer.allocate(1+48+8+4+purpose.length+4+keyId.length);
            aad.put((byte)1); uuid(aad,c.tenantId()); uuid(aad,c.projectId()); uuid(aad,c.id());
            aad.putLong(c.credentialRevision()).putInt(purpose.length).put(purpose).putInt(keyId.length).put(keyId);
            cipher.updateAAD(aad.array()); return cipher.doFinal(input);
        } catch (GeneralSecurityException ignored) { throw failure(); }
    }
    private static void uuid(ByteBuffer b, UUID id) { b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static ModelCredentialProtectionException failure() { return new ModelCredentialProtectionException(); }
}
