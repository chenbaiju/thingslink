package com.things.link.ota.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 独立AES-256-GCM响应信封，没有默认成功密钥或生产私钥生成。 */
@Component
public final class AesGcmOtaDownloadResponseCipher implements OtaDownloadResponseCipher {
    /** 当前唯一加密版本。 */
    private final String activeVersion;
    /** 旧版本只用于恢复已有密文。 */
    private final Map<String, SecretKeySpec> keys;
    /** 每次独立nonce来源。 */
    private final SecureRandom random = new SecureRandom();

    /** 双空表示未配置；半配、非法版本、编码和长度均启动失败。 */
    public AesGcmOtaDownloadResponseCipher(
            @Value("${things-link.ota.download-response.keyring-json:}") String keyringJson,
            @Value("${things-link.ota.download-response.active-key-version:}") String activeVersion) {
        if (keyringJson != null && keyringJson.isBlank() && activeVersion != null && activeVersion.isBlank()) {
            this.keys = Map.of();
            this.activeVersion = null;
            return;
        }
        try {
            if (keyringJson == null || keyringJson.getBytes(StandardCharsets.UTF_8).length > 8192
                    || activeVersion == null || !activeVersion.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw failure();
            var parsed = new OtaCanonicalJson().parseObject(keyringJson.getBytes(StandardCharsets.UTF_8));
            if (parsed.isEmpty() || parsed.size() > 16) throw failure();
            Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
            for (var entry : parsed.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                        || !(entry.getValue() instanceof String text)) throw failure();
                byte[] material = Base64.getDecoder().decode(text);
                try {
                    if (material.length != 32 || !Base64.getEncoder().encodeToString(material).equals(text)) throw failure();
                    ring.put(entry.getKey(), new SecretKeySpec(material, "AES"));
                } finally { Arrays.fill(material, (byte) 0); }
            }
            if (!ring.containsKey(activeVersion)) throw failure();
            this.keys = Map.copyOf(ring);
            this.activeVersion = activeVersion;
        } catch (RuntimeException invalid) { throw failure(); }
    }

    /** 缺配置不提供成功实现。 */
    @Override public boolean configured() { return !keys.isEmpty(); }

    /** 随机nonce加密完整有界响应，AAD错误不能生成可移植信封。 */
    @Override public Envelope encrypt(Binding binding, byte[] plaintext) {
        if (!configured() || plaintext == null || plaintext.length == 0 || plaintext.length > 65536) throw failure();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, binding, plaintext, nonce, activeVersion);
        return new Envelope(activeVersion, nonce, encrypted);
    }

    /** 恢复原版本，认证失败不保留原异常或明文。 */
    @Override public byte[] decrypt(Binding binding, Envelope envelope) {
        if (envelope == null || envelope.nonce().length != 12 || envelope.ciphertext().length < 17
                || envelope.ciphertext().length > 65552) throw failure();
        return crypt(Cipher.DECRYPT_MODE, binding, envelope.ciphertext(), envelope.nonce(), envelope.keyVersion());
    }

    /** 固定算法和128bit认证标签，所有密钥/正文异常固定分类。 */
    private byte[] crypt(int mode, Binding binding, byte[] input, byte[] nonce, String keyVersion) {
        try {
            SecretKeySpec key = keys.get(keyVersion);
            if (key == null) throw failure();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(binding, keyVersion));
            return cipher.doFinal(input);
        } catch (Exception invalid) { throw failure(); }
    }

    /** 定宽UUID/整数与长度前缀文本消除拼接歧义，绑定完整原身份和绝对期限。 */
    private static byte[] aad(Binding binding, String keyVersion) throws java.io.IOException {
        if (binding == null || binding.credentialVersion() < 0 || binding.attemptNo() < 1
                || binding.manifestSha256() == null || !binding.manifestSha256().matches("[0-9a-f]{64}")
                || !"tc-ota-download-response/v1".equals(binding.contractVersion()) || binding.expiresAt() == null) throw failure();
        OtaDownloadResponseCodec.instant(binding.expiresAt().toString());
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("thingslink-ota-download-response-aad-v1");
            output.writeUTF(keyVersion);
            uuid(output, binding.tenantId());
            uuid(output, binding.projectId());
            uuid(output, binding.deviceId());
            output.writeLong(binding.credentialVersion());
            uuid(output, binding.requestId());
            uuid(output, binding.jobId());
            uuid(output, binding.authorizationId());
            output.writeInt(binding.attemptNo());
            output.writeUTF(binding.manifestSha256());
            output.writeLong(binding.expiresAt().getEpochSecond());
            output.writeInt(binding.expiresAt().getNano());
            output.writeUTF(binding.contractVersion());
        }
        return bytes.toByteArray();
    }

    /** UUID固定十六字节，不接受空轴。 */
    private static void uuid(DataOutputStream output, UUID id) throws java.io.IOException {
        if (id == null) throw failure();
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    /** 不携带供应商异常、密钥版本正文或明文。 */
    private static IllegalArgumentException failure() {
        return new IllegalArgumentException("OTA下载响应加密配置或信封认证失败");
    }
}
