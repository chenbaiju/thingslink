package com.things.link.rule.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 唯一未来游标与逐版本持久下界；调用方持项目许可、自动化锁和定义行锁。 */
public interface AutomationScheduleRepository {
    record Candidate(UUID tenantId,UUID projectId,UUID automationId){}
    record Claim(Candidate candidate,UUID versionId,Instant fireAt,long token,Instant leaseUntil){}
    java.util.List<Candidate> candidates(UUID afterTenant);
    Optional<Claim> lock(Candidate candidate);
    Optional<Claim> claim(Candidate candidate);
    boolean advance(Claim claim,Instant next,boolean oneShot);
    void boundedWait();
    Instant nextFire(UUID project,UUID automation);
    record State(Instant floor,boolean consumed){}
    Optional<State> state(UUID project,UUID version);
    void activate(AutomationVersion version,Instant next);
    void clear(UUID project,UUID automation);
}
