package com.things.link.enduser.application;

/**
 * 厂商 token 的信封密文。
 *
 * @param cipherText AES-GCM 密文与认证标签
 * @param nonce 96 bit 随机 nonce
 * @param keyId 非敏感密钥版本
 */
public record EncryptedPushToken(byte[] cipherText, byte[] nonce, String keyId) {

    /** 防御性复制敏感字节，避免调用方在加密后改写仓储待持久化内容。 */
    public EncryptedPushToken {
        cipherText = cipherText.clone();
        nonce = nonce.clone();
    }

    /** @return 密文副本，调用方不能修改记录内部状态 */
    @Override
    public byte[] cipherText() {
        return cipherText.clone();
    }

    /** @return nonce 副本，调用方不能破坏认证参数 */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }
}
