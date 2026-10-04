package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppPushToken;

/** PUSH token 加解密端口；领域与仓储不接触主密钥。 */
public interface PushTokenCipher {

    /**
     * 使用当前 active key 加密并把事实身份写入 AAD。
     *
     * @param token 安装实例元数据
     * @param plainToken 厂商 token 明文
     * @return 信封密文
     */
    EncryptedPushToken encrypt(AppPushToken token, String plainToken);

    /**
     * 按信封 keyId 解密；认证或密钥错误必须抛错。
     *
     * @param token 安装实例元数据
     * @param encrypted 信封密文
     * @return 厂商 token 明文
     */
    String decrypt(AppPushToken token, EncryptedPushToken encrypted);
}
