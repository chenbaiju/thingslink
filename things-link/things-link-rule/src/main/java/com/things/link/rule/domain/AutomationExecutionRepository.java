package com.things.link.rule.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自有执行及尝试事实；调用方必须遵循项目/定义/执行锁序。 */
public interface AutomationExecutionRepository {
    List<Candidate> candidates();
    Optional<AutomationExecution> lock(Candidate candidate);
    Instant now();
    AutomationExecution claim(Candidate candidate);
    boolean finish(AutomationExecution execution, String status, String reason);
    boolean retry(AutomationExecution execution);
    void expire(AutomationExecution execution);
    record Candidate(UUID tenantId, UUID projectId, UUID automationId, UUID executionId) {}
}
