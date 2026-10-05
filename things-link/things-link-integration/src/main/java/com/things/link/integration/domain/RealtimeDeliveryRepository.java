package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
/** 实时交付持久端口，负责候选定位、加锁、租约认领及带令牌的状态推进。 */
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
