package com.things.link.project.infrastructure.security;

import com.things.link.project.application.ProjectInvitationCodes;
import com.things.link.project.domain.ProjectInvitation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/** 以独立域派生邀请HMAC密钥，绑定不可变收件上下文及当前修订，不存储明文秘密。 */
@Component
@ConditionalOnProperty(name = "things-link.security.jwt.secret")
public final class HmacProjectInvitationCodes implements ProjectInvitationCodes {
    private final byte[] key;
    public HmacProjectInvitationCodes(@Value("${things-link.security.jwt.secret}") String secret) {
        byte[] root = secret.getBytes(StandardCharsets.UTF_8);
        if (root.length < 32) throw new IllegalArgumentException("邀请签名配置不满足密钥长度要求");
        key = sign(root, "things-link/project-invitation/v1");
    }
    @Override public String issue(ProjectInvitation invitation) {
        String binding = invitation.id() + "|" + invitation.tenantId() + "|" + invitation.projectId() + "|"
                + invitation.inviterAccountId() + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(invitation.targetEmail().getBytes(StandardCharsets.UTF_8)) + "|" + invitation.role() + "|"
                + invitation.revision() + "|" + invitation.codeNonce() + "|" + invitation.expiresAt();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(key, binding));
    }
    @Override public boolean matches(ProjectInvitation invitation, String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{43}")
                && MessageDigest.isEqual(issue(invitation).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] sign(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("邀请签名不可用", failure); }
    }
}
