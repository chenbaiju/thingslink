package com.things.link.integration.domain;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** 固定精确凭据查找；无租户上下文不能使用普通业务表查询。 */
public interface ApiKeyAuthenticationRepository {
    Optional<Proof> prove(UUID keyId,String digest,String sourceIp);
    record Proof(UUID keyId,UUID tenant,UUID project,long generation,UUID issuer,List<String> scopes,Instant expiresAt){
        public Proof {scopes=List.copyOf(scopes);}
    }
}
