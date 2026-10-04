package com.things.link.rule.domain;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** 项目候选、已知设备的执行尝试、原设备动作独立分页。 */
public interface DeviceMessageRuleRepository {
    List<DeviceMessageRuleItem> definitions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit);
    List<DeviceMessageRuleExecutionItem> history(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit);
    List<DeviceMessageRuleActionItem> actions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit);
}
