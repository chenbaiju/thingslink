package com.things.link.integration.domain;
import com.things.link.shared.message.PublicWebhookEvent;
import java.time.Instant;
import java.util.*;
/** 可信 Webhook 来源及投递意图持久端口；来源身份、正文摘要和原始时间独立保存。 */
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
