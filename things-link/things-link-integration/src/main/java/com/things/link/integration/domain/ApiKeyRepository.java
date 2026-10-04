package com.things.link.integration.domain;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** 持久端口；调用方持项目管理锁及完整RLS范围，事务外不可变更。 */
public interface ApiKeyRepository {
    Instant now();
    List<String> canonicalCidrs(List<String> cidrs);
    Optional<ApiKeyFact> lockForDelivery(UUID tenant,UUID project,UUID id);
    Optional<ApiKeyFact> find(UUID tenant, UUID project, UUID id);
    Optional<ApiKeyOperation> operation(UUID tenant, UUID project, UUID operation);
    int activeCount(UUID tenant, UUID project, Instant now);
    List<ApiKeyFact> page(UUID tenant, UUID project, UUID after, int limit);
    void insert(ApiKeyFact key);
    void revoke(UUID tenant, UUID project, UUID id, Instant now);
    void complete(ApiKeyOperation operation);
}
