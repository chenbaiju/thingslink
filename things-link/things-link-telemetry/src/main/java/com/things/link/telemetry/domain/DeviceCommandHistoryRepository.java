package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 目标设备先过滤、后键集分页的命令事实读取端口。 */
public interface DeviceCommandHistoryRepository {
    /** 同时刻以命令ID打破并列；fetchLimit包含探测下一页的一行。 */
    List<DeviceCommandHistoryItem> find(UUID projectId, UUID deviceId, Instant beforeTime, UUID beforeId, int fetchLimit);
}
