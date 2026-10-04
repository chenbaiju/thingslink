package com.things.link.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR0211：管理专用安全投影，禁止携带正文、秘密和租约。 */
public interface WebhookQueryRepository {
    List<DeliveryView> deliveries(UUID project, UUID subscription, String status, Instant beforeTime, UUID beforeId, int limit);
    Optional<DeliveryDetail> detail(UUID project, UUID id);
    List<EventView> events(UUID project, String type, String result, Instant beforeTime, UUID beforeId, int limit);
    record DeliveryView(UUID id, UUID subscriptionId, String subscriptionRevision, String eventType, UUID eventId,
            String status, int attempts, int round, int roundAttempts, Instant createdAt, Instant deadlineAt,
            Instant nextAttemptAt, Instant terminalAt, String reason) { }
    record AttemptView(int attemptNo, int round, Instant startedAt, Instant finishedAt, String result,
            Integer httpStatus, String elapsedMillis, String reason) { }
    record DeliveryDetail(DeliveryView delivery, String name, String targetUrl, List<AttemptView> attempts) { }
    record EventView(String eventType, UUID eventId, Instant sourceOccurredAt, Instant recordedAt,
            Instant acceptedAt, String result, String sourceHash, boolean hasConflict) { }
}
