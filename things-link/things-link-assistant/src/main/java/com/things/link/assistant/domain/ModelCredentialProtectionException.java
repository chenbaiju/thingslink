package com.things.link.assistant.domain;

/** 预期的凭据保护不可用；固定消息，无秘密、底层异常或配置值。 */
public final class ModelCredentialProtectionException extends IllegalStateException {
    public ModelCredentialProtectionException() {
        super("Agent 凭据加密配置不可用或密文认证失败", null);
    }
}
