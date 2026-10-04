package com.things.link.rule.application.automation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/** 应用层只读端口以显式脱敏投影隔离持久私有输入。 */
public interface AutomationExecutionReadPort {
    Instant now();
    List<AutomationExecutionView> page(UUID project,UUID automation,String status,Instant from,Instant to,Instant before,UUID beforeId,int limit);
    Optional<AutomationExecutionDetailView> detail(UUID project,UUID id);
}
