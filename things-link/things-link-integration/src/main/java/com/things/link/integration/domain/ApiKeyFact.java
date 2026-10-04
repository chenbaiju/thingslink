package com.things.link.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 持久Key事实；不是API响应，不包含明文秘密，诊断不输出摘要。 */
public record ApiKeyFact(UUID id, UUID tenantId, UUID projectId, long generation, UUID issuer,
                         String name, String secretHash, List<String> scopes, List<String> cidrs,
                         String status, Instant createdAt, Instant expiresAt, Instant revokedAt, long revision) {
    public ApiKeyFact { scopes=List.copyOf(scopes); cidrs=List.copyOf(cidrs); }
    @Override public String toString() { return "ApiKeyFact[id="+id+", status="+status+"]"; }
}
