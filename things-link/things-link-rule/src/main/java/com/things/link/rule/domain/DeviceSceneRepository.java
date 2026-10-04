package com.things.link.rule.domain;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** 场景当前发布设备与原执行设备分别查询，不改写任何执行事实。 */
public interface DeviceSceneRepository {
    List<DeviceSceneItem> definitions(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit);
    List<DeviceSceneExecutionItem> history(UUID project,UUID device,Instant beforeTime,UUID beforeId,int limit);
}
