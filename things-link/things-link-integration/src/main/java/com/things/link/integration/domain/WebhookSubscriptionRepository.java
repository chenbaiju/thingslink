package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
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
