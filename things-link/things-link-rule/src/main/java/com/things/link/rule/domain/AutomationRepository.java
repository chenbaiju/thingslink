package com.things.link.rule.domain;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自动化自有定义/版本/执行持久端口；所有写操作由项目事务串行。 */
public interface AutomationRepository {
    Optional<AutomationDefinition> find(UUID project,UUID id,boolean lock);
    Optional<AutomationVersion> version(UUID project,UUID id,UUID version);
    List<AutomationVersion> matching(UUID project,UUID device);
    List<AutomationDefinition> page(UUID project,String name,String status,Instant before,UUID beforeId,int limit);
    List<AutomationVersion> history(UUID project,UUID id,Long before,int limit);
    int countDefinitions(UUID project);
    int countActiveSubscriptions(UUID project,UUID device,UUID excluding);
    int countPending(UUID project);
    long nextVersion(UUID project,UUID id);
    void create(AutomationDefinition definition,AutomationVersion version);
    boolean revise(AutomationDefinition definition,long expected,AutomationVersion version);
    boolean activate(UUID project,UUID id,UUID version,long expected);
    boolean pause(UUID project,UUID id,long expected);
    boolean delete(UUID project,UUID id,long expected);
    void admitTime(UUID id,AutomationVersion version,Instant fireAt,UUID device,JsonNode input,String digest,String reason,Instant now);
    void admit(UUID id,AutomationVersion version,UUID sourceEvent,UUID device,JsonNode input,String digest,
               String trace,String reason,Instant now,Instant occurredAt,Instant acceptedAt);
}
