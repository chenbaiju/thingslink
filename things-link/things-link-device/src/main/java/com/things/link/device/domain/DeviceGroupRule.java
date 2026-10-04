package com.things.link.device.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 动态组允许的条件白名单；维度间固定 AND，至少一个维度非空。
 *
 * @param deviceTypeIds 可接受的设备类型集合
 * @param statuses 可接受的设备状态集合
 * @param tags 需要匹配的标签键值
 * @param tagMatch 多标签 ANY/ALL 语义；没有标签时必须为空
 */
public record DeviceGroupRule(Set<UUID> deviceTypeIds, Set<Device.Status> statuses, Map<String, String> tags,
                              TagMatch tagMatch) {
    /** 多标签匹配语义。 */
    public enum TagMatch {
        /** 任一标签命中。 */
        ANY,
        /** 所有标签均命中。 */
        ALL
    }
    /** 构造时拒绝任意字段型表达式，避免动态组成为可注入的 SQL/DSL 入口。 */
    public DeviceGroupRule {
        deviceTypeIds = deviceTypeIds == null ? Set.of() : Set.copyOf(deviceTypeIds);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        if (deviceTypeIds.isEmpty() && statuses.isEmpty() && tags.isEmpty()) {
            throw new IllegalArgumentException("动态组规则不能为空");
        }
        if (tags.isEmpty() && tagMatch != null || !tags.isEmpty() && tagMatch == null) {
            throw new IllegalArgumentException("动态组标签匹配方式不合法");
        }
    }
}
