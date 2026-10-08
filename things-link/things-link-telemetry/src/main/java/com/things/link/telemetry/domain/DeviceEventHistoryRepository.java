package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 完整当前范围及有效窗口先过滤，后做键集分页；不解释当前模型。 */
public interface DeviceEventHistoryRepository {
    /** 同刻以消息ID排序，fetchLimit包括下一页探测行。 */
    List<DeviceEventHistoryItem> find(UUID projectId, UUID deviceId, String eventKey, String level, UUID versionId,
                                     Instant from, Instant to, Instant beforeTime, UUID beforeId, int fetchLimit);
    /** 详情沿相同设备范围及有效窗口，不暴露范围外记录存在性。 */
    Optional<DeviceEventHistoryItem> findOne(UUID projectId, UUID deviceId, UUID messageId, Instant from, Instant to);
}
