package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
/** Webhook 订阅及管理操作回执持久端口；读写范围由调用方建立事务隔离。 */
public interface WebhookSubscriptionRepository {
    Instant now();
    Optional<WebhookSubscription> find(UUID id);
    Optional<WebhookSubscription> lockForDelivery(UUID id);
    List<WebhookSubscription> page(UUID project,UUID after,int limit);
    Optional<WebhookOperation> operation(UUID operation);
    void insert(WebhookSubscription value);
    void revise(WebhookSubscription value,long expected);
    void complete(WebhookOperation operation);
}
