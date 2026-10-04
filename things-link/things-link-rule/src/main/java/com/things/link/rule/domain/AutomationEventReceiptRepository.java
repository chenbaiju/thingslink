package com.things.link.rule.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 事件受理原事务内的持久边界；计划生成与资格判定由受理服务负责。 */
public interface AutomationEventReceiptRepository {
    Instant databaseNow();
    void lockProject(UUID projectId);
    Optional<AutomationEventReceipt> find(UUID projectId, UUID sourceEventId);
    void insert(AutomationEventReceipt receipt);
    UUID rejectTransport(UUID id, int partition, long offset, String digest, String reason);
}
