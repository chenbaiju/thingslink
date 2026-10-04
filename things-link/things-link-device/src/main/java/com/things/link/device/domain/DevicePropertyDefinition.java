package com.things.link.device.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 设备类型的单个属性定义；S2-3 先覆盖能够完成温度和开关验收的基础类型。
 * @param id 属性 ID @param tenantId 租户 ID @param projectId 项目 ID @param deviceTypeId 设备类型 ID
 * @param propertyKey 消息键 @param name 展示名称 @param accessType 流向 @param dataType 数据类型
 * @param unit 单位 @param decimalPlaces 精度 @param minimumValue 最小值 @param maximumValue 最大值
 * @param enumOptions 枚举值 @param onLabel 开启文字 @param offLabel 关闭文字
 * @param schema OBJECT/LIST 使用的规范 Schema，标量必须为空 @param sortOrder 排序
 * @param createdAt 创建时间
 */
public record DevicePropertyDefinition(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                       String propertyKey, String name, AccessType accessType, DataType dataType,
                                       String unit, Integer decimalPlaces, BigDecimal minimumValue,
                                       BigDecimal maximumValue, List<String> enumOptions, String onLabel,
                                       String offLabel, String schema, int sortOrder, Instant createdAt) {
    /** B-X1a 前标量调用兼容构造器；既有四种类型没有独立 Schema。 */
    public DevicePropertyDefinition(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                    String propertyKey, String name, AccessType accessType, DataType dataType,
                                    String unit, Integer decimalPlaces, BigDecimal minimumValue,
                                    BigDecimal maximumValue, List<String> enumOptions, String onLabel,
                                    String offLabel, int sortOrder, Instant createdAt) {
        this(id, tenantId, projectId, deviceTypeId, propertyKey, name, accessType, dataType, unit,
                decimalPlaces, minimumValue, maximumValue, enumOptions, onLabel, offLabel, null,
                sortOrder, createdAt);
    }
    /** 属性在设备端和云平台之间的合法流向。 */
    public enum AccessType {
        /** 未限制流向。 */ UNDEFINED, /** 仅设备上报。 */ REPORT, /** 仅云端下发。 */ DOWNLINK,
        /** 设备上报且云端可下发。 */ SHARED, /** 仅云端存储和更新。 */ CLOUD_PRIVATE
    }

    /** X-01 唯一类型集合；复合值保持原生 JSON，不拆成嵌套路径。 */
    public enum DataType {
        /** 任意整数或浮点数。 */ NUMBER, /** Plaintext 文本。 */ TEXT,
        /** 布尔开关量。 */ SWITCH, /** 限定字符串集合。 */ ENUM,
        /** 原子 JSON 对象快照。 */ OBJECT, /** 有序同构 JSON 数组快照。 */ LIST
    }
}
