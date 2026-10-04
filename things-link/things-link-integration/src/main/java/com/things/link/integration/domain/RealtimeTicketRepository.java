package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
public interface RealtimeTicketRepository {
    Instant now();
    void insert(RealtimeTicket ticket,String digest);
    Optional<RealtimeTicket> prove(UUID id,String digest);
    Optional<RealtimeTicket> lockForTermination(UUID id);
    Optional<RealtimeTicket> lockForDelivery(UUID id);
    Optional<RealtimeTicket> find(UUID id);
    Optional<RealtimeTicket> connected(UUID id);
    List<RealtimeTicket> lockConnected(UUID project);
    boolean bindWs(UUID id,String peerIp,String instance);
    String closedReason(UUID id);
    boolean bindMqtt(UUID id,String peerIp);
    boolean ipAllowed(String ip,List<String> cidrs);
    void close(UUID id,String reason);
    List<Candidate> candidates(String instance,int limit);
    int purgeExpired(int limit);
    record Candidate(UUID tenant,UUID project,UUID id) {}
}
