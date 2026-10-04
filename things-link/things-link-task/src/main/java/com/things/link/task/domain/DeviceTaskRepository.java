package com.things.link.task.domain;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** 设备详情的任务域只读端口，当前配置和原目标事实分别读取。 */
public interface DeviceTaskRepository {
    List<DeviceTaskJobItem> candidates(UUID projectId, Instant beforeTime, UUID beforeId, int limit);
    List<DeviceTaskExecutionItem> history(UUID projectId, UUID deviceId, Instant beforeTime, UUID beforeId, int limit);
}
