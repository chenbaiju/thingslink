package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
public interface WebhookDeliveryRepository {
    List<Candidate> candidates(int limit);
    Optional<Delivery> find(UUID id);
    Optional<Delivery> lock(UUID id);
    String eventText(UUID id);
    Delivery claim(UUID id,UUID token);
    void completeAttempt(UUID id,UUID token,String result,Integer httpStatus,Long elapsed,String reason);
    boolean finish(UUID id,UUID token,String status,String reason,int delay);
    boolean recover(UUID id,int expectedRound);
    void terminate(UUID id,String status,String reason);
    record Candidate(UUID tenant,UUID project,UUID id){}
    record Delivery(UUID id,UUID subscription,long revision,String eventType,UUID eventId,String status,int attempts,int round,int roundAttempts,
        Instant createdAt,Instant deadlineAt,Instant nextAttempt,UUID token,Instant leaseUntil,Instant terminalAt){}
}
