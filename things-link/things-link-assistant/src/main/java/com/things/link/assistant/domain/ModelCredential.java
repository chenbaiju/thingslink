package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.UUID;

/** 只在领域内部流转的密文事实；credentialRevision 是 AAD 版本，停用不要求解密。 */
public record ModelCredential(UUID id, UUID tenantId, UUID projectId, long revision,
        long credentialRevision, boolean enabled, byte[] ciphertext, byte[] nonce, String keyId,
        UUID updatedBy, Instant updatedAt) {
    public static final String PURPOSE = "deepseek-chat";
    public ModelCredential {
        ciphertext = ciphertext == null ? null : ciphertext.clone();
        nonce = nonce == null ? null : nonce.clone();
    }
    @Override public byte[] ciphertext() { return ciphertext == null ? null : ciphertext.clone(); }
    @Override public byte[] nonce() { return nonce == null ? null : nonce.clone(); }
    @Override public String toString() { return "ModelCredential[REDACTED]"; }
}
