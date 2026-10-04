package com.things.link.integration.application;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** 无秘密/摘要的管理元数据；状态过期单独派生，不伪造撤销时间。 */
public record ApiKeyView(UUID id, UUID projectId, long projectGeneration, UUID issuerAccountId,
                         String name, List<String> scopes, List<String> ipCidrs, String status,
                         Instant createdAt, Instant expiresAt, Instant revokedAt, long revision) {
    public ApiKeyView { scopes=List.copyOf(scopes); ipCidrs=List.copyOf(ipCidrs); }
}
