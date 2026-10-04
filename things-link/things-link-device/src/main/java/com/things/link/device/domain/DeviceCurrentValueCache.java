package com.things.link.device.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Redis 热影子端口；实现故障时由应用服务回退 PostgreSQL，不允许改变事实事务结果。 */
public interface DeviceCurrentValueCache {
    /**
     * 批量读取精确的设备属性组合。
     *
     * @param projectId 项目隔离轴
     * @param keys 待读取组合
     * @return 命中的当前值，未命中组合不出现在结果中
     */
    Map<ValueKey, DeviceCurrentValue> findAll(UUID projectId, Collection<ValueKey> keys);

    /** 仅合并带有已知PG接受序号的属性，旧序号与同序号不得覆盖。 */
    void merge(UUID projectId, UUID deviceId, Map<String, ReportedValue> values);

    /** 单属性已接受事实。
     * @param jsonValue 原JSON值 @param occurredAt 完整发生时间 @param shadowVersion desired版本
     * @param reportedRevision 规范正Long文本 @param thingModelVersionId 可空写入模型来源 */
    record ReportedValue(String jsonValue, Instant occurredAt, int shadowVersion,
                         String reportedRevision, UUID thingModelVersionId) {
        /** 只允许已知合法序号进入新缓存，保留完整发生时间。 */
        public ReportedValue {
            com.things.link.shared.message.ReportedRevision.require(reportedRevision);
            java.util.Objects.requireNonNull(jsonValue);
            java.util.Objects.requireNonNull(occurredAt);
            if (shadowVersion < 0) throw new IllegalArgumentException("desired版本不得为负");
        }
    }

    /**
     * 当前值组合键。
     *
     * @param deviceId 设备 ID
     * @param propertyKey 属性键
     */
    record ValueKey(UUID deviceId, String propertyKey) {
    }
}
