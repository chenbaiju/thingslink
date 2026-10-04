package com.things.link.integration.application;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
/** 经各自安全链验证的主体；构造器不执行JWT认证，也不能由请求体绑定。 */
public record RealtimeIdentity(Kind kind,UUID tenant,UUID project,long generation,UUID subject,
                               UUID account,UUID key,Instant identityExpiresAt) {
    public enum Kind { CONSOLE, APP, API_KEY }
    public RealtimeIdentity {
        Objects.requireNonNull(kind);Objects.requireNonNull(tenant);Objects.requireNonNull(project);
        Objects.requireNonNull(subject);Objects.requireNonNull(identityExpiresAt);
        if(generation<0 || kind==Kind.APP&&(account!=null||key!=null)
            ||kind==Kind.CONSOLE&&(!subject.equals(account)||key!=null)
            ||kind==Kind.API_KEY&&(account==null||!subject.equals(key)))throw new IllegalArgumentException("实时主体组合无效");
    }
    public static RealtimeIdentity fromKey(ApiKeyPrincipal key) {
        key.require("device:read",false);
        return new RealtimeIdentity(Kind.API_KEY,key.tenantId(),key.projectId(),key.generation(),key.keyId(),key.issuerAccountId(),key.keyId(),key.expiresAt());
    }
    public UUID budgetSubject(){return kind==Kind.APP?subject:account;}
    public String budgetKind(){return kind==Kind.APP?"APP":"ACCOUNT";}
}
