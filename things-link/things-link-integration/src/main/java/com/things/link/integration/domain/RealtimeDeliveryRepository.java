package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
public interface RealtimeDeliveryRepository {
    List<Candidate> candidates(int limit);
    List<Candidate> wsCandidates(UUID ticket,int limit);
    Optional<Delivery> lock(UUID id);
    Delivery claim(UUID id,UUID token);
    boolean finish(UUID id,UUID token,String status,String reason,int delaySeconds);
    void terminate(UUID id,String status,String reason);
    record Candidate(UUID tenant,UUID project,UUID id) {}
    record Delivery(UUID id,UUID ticket,String envelope,String status,int attempts,Instant nextAttempt,UUID token,Instant leaseUntil) {}
}
