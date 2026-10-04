package com.things.link.integration.domain;
import com.things.link.shared.message.PublicWebhookEvent;
import java.time.Instant;
import java.util.*;
public interface WebhookEventRepository {
    Instant now();
    void lockProject(UUID project);
    Instant advanceFloor(UUID tenant,UUID project);
    Optional<Existing> find(PublicWebhookEvent event);
    void conflict(PublicWebhookEvent event,String hash,Instant now);
    List<Plan> matching(PublicWebhookEvent event);
    int pending(UUID project);
    void insert(PublicWebhookEvent event,String hash,String eventText,String result,Instant now);
    void enqueue(PublicWebhookEvent event,Plan plan,Instant now);
    record Existing(String hash,String result){}
    record Plan(UUID subscription,long revision){}
}
