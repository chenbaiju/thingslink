package com.things.link.ota.application;

import java.time.Instant;
import java.util.UUID;

/** 下载响应秘密信封；独立密钥环不借用固件签名密钥或PUSH身份。 */
public interface OtaDownloadResponseCipher {
    /** 是否有完整可用受控配置。 */
    boolean configured();
    /** 仅使用当前active版本，随机nonce绑定稳定授权事实。 */
    Envelope encrypt(Binding binding, byte[] plaintext);
    /** 按信封原版本解密；未知旧版本或错误AAD必须拒绝。 */
    byte[] decrypt(Binding binding, Envelope envelope);

    /** 完整不可变关联数据。
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 设备
     * @param credentialVersion 原凭据代际
     * @param requestId 申请
     * @param jobId 作业
     * @param authorizationId 授权
     * @param attemptNo 尝试
     * @param manifestSha256 发布清单摘要
     * @param expiresAt 固定绝对截止
     * @param contractVersion 固定响应合同
     */
    record Binding(UUID tenantId, UUID projectId, UUID deviceId, long credentialVersion,
            UUID requestId, UUID jobId, UUID authorizationId, int attemptNo, String manifestSha256,
            Instant expiresAt, String contractVersion) {
        /** 只输出非秘密授权身份。 */
        @Override public String toString() { return "DownloadResponseBinding[authorizationId=" + authorizationId + "]"; }
    }

    /** AEAD密文及版本，字段不能通过默认字符串意外进入日志。
     * @param keyVersion 独立加密密钥版本
     * @param nonce 每次随机12字节nonce
     * @param ciphertext 含128bit认证标签的密文
     */
    record Envelope(String keyVersion, byte[] nonce, byte[] ciphertext) {
        /** 冻结全部可变字节。 */
        public Envelope { nonce = nonce.clone(); ciphertext = ciphertext.clone(); }
        /** 独立nonce副本。 */ @Override public byte[] nonce() { return nonce.clone(); }
        /** 独立密文副本。 */ @Override public byte[] ciphertext() { return ciphertext.clone(); }
        /** 禁止打印密文或秘密关联数据。 */
        @Override public String toString() { return "DownloadResponseEnvelope[redacted]"; }
    }
}
