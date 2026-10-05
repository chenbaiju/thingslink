package com.things.link.dashboard.api.dto.response;

import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceCurrentResult;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelDescription;
import com.things.link.device.application.RuntimePropertyDescription;
import com.things.link.dashboard.application.DashboardShareAlarmPage;
import com.things.link.dashboard.application.DashboardShareCatalogPage;
import com.things.link.device.application.RuntimeDeviceCatalogItem;
import com.things.link.telemetry.application.AppVersionedHistoryPoint;
import com.things.link.telemetry.application.AppVersionedPropertyHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 五条匿名分享运行数据接口的封闭响应类型及无权限字段映射。 */
public final class DashboardShareDataResponse {
    /** 禁止实例化纯DTO命名空间。 */
    private DashboardShareDataResponse() { }

    /** @param devices 请求顺序设备状态 @param models 实际成功设备使用的模型描述 */
    @Schema(name = "DashboardShareDeviceSnapshotResponse")
    public record Snapshot(
            @NotNull @ArraySchema(maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<SnapshotDevice> devices,
            @NotNull @ArraySchema(maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Model> models) {
        /** 从device公开端口精确投影。 */
        public static Snapshot from(RuntimeDeviceSnapshotResult source) {
            return new Snapshot(source.devices().stream().map(SnapshotDevice::from).toList(),
                    source.models().stream().map(Model::from).toList());
        }
    }

    /** 设备快照三态；失败字段用缺失表达，MODEL_MISMATCH的当前模型字段允许显式null。 */
    @Schema(name = "DashboardShareDeviceSnapshotItem", oneOf = {
            UnavailableSnapshotDevice.class, MismatchedSnapshotDevice.class, AvailableSnapshotDevice.class})
    public sealed interface SnapshotDevice permits UnavailableSnapshotDevice,
            MismatchedSnapshotDevice, AvailableSnapshotDevice {
        /** @return 请求设备ID */ UUID deviceId();
        /** @return 三态机器值 */ String status();
        /** 保持device端已裁决的状态与字段组合，失败分支不序列化不应出现的null字段。 */
        private static SnapshotDevice from(RuntimeDeviceAvailability source) {
            return switch (source.status()) {
                case NOT_AVAILABLE -> new UnavailableSnapshotDevice(source.deviceId(), source.status().name());
                case MODEL_MISMATCH -> new MismatchedSnapshotDevice(
                        source.deviceId(), source.status().name(), source.currentModelVersionId());
                case AVAILABLE -> new AvailableSnapshotDevice(source.deviceId(), source.status().name(),
                        source.name(), source.deviceStatus(), source.lastOnlineAt(), source.currentModelVersionId());
            };
        }
    }

    /** @param deviceId 请求设备 @param status 固定NOT_AVAILABLE */
    @Schema(name = "DashboardShareUnavailableSnapshotDevice")
    public record UnavailableSnapshotDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "NOT_AVAILABLE") String status)
            implements SnapshotDevice { }

    /** @param deviceId 请求设备 @param status 固定MODEL_MISMATCH @param currentModelVersionId 当前模型，可为null */
    @Schema(name = "DashboardShareMismatchedSnapshotDevice")
    public record MismatchedSnapshotDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MODEL_MISMATCH") String status,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID currentModelVersionId)
            implements SnapshotDevice { }

    /**
     * @param deviceId 请求设备 @param status 固定AVAILABLE @param name 设备名 @param deviceStatus 设备状态
     * @param lastOnlineAt 最近在线时间，可为null @param currentModelVersionId 当前模型
     */
    @Schema(name = "DashboardShareAvailableSnapshotDevice")
    public record AvailableSnapshotDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "AVAILABLE") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"INACTIVE", "ONLINE", "OFFLINE"}) String deviceStatus,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant lastOnlineAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID currentModelVersionId) implements SnapshotDevice { }

    /**
     * 模型运行描述。
     * @param versionId 模型版本
     * @param digestAlgorithm 摘要算法
     * @param digest 摘要
     * @param profile 模型运行配置档案
     * @param properties 实际请求键并集的属性描述
     */
    @Schema(name = "DashboardShareRuntimeModelResponse")
    public record Model(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID versionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String digestAlgorithm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String digest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String profile,
            @NotNull @ArraySchema(maxItems = 200, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Property> properties) {
        /** 映射模型描述。 */
        private static Model from(RuntimeModelDescription source) {
            return new Model(source.versionId(), source.digestAlgorithm(), source.digest(), source.profile(),
                    source.properties().stream().map(Property::from).toList());
        }
    }

    /**
     * 模型顶层属性描述。
     * @param propertyKey 属性键 @param dataType 数据类型 @param unit 单位
     * @param minimumValue 下界 @param maximumValue 上界 @param enumOptions 枚举选项
     * @param onLabel 开启文案 @param offLabel 关闭文案
     */
    @Schema(name = "DashboardShareRuntimePropertyResponse")
    public record Property(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String propertyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NUMBER", "TEXT", "SWITCH", "ENUM", "OBJECT", "LIST"}) String dataType,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String unit,
            @Schema(types = {"number", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal minimumValue,
            @Schema(types = {"number", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal maximumValue,
            @Schema(types = {"array", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> enumOptions,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String onLabel,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String offLabel) {
        /** 映射不可变属性元数据。 */
        private static Property from(RuntimePropertyDescription source) {
            return new Property(source.propertyKey(), source.dataType(), source.unit(), source.minimumValue(),
                    source.maximumValue(), source.enumOptions(), source.onLabel(), source.offLabel());
        }
    }

    /** @param devices 请求顺序的当前值设备结果 */
    @Schema(name = "DashboardShareCurrentValueResponse")
    public record Current(
            @NotNull @ArraySchema(minItems = 1, maxItems = 20,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<CurrentDevice> devices) {
        /** 映射当前值结果。 */
        public static Current from(RuntimeDeviceCurrentResult source) {
            return new Current(source.devices().stream().map(CurrentDevice::from).toList());
        }
    }

    /** @param deviceId 设备ID @param status 三态 @param values 可用设备完整键结果 */
    @Schema(name = "DashboardShareCurrentValueDeviceResponse")
    public record CurrentDevice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NOT_AVAILABLE", "MODEL_MISMATCH", "AVAILABLE"}) String status,
            @NotNull @ArraySchema(maxItems = 50, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Value> values) {
        /** 映射单设备当前值。 */
        private static CurrentDevice from(RuntimeDeviceCurrentResult.DeviceValues source) {
            return new CurrentDevice(source.deviceId(), source.status().name(),
                    source.values().stream().map(Value::from).toList());
        }
    }

    /** 当前值状态；只有VALUE分支输出业务事实字段。 */
    @Schema(name = "DashboardShareCurrentPropertyResponse", oneOf = {AvailableValue.class, EmptyValue.class})
    public sealed interface Value permits AvailableValue, EmptyValue {
        /** @return 属性键 */ String propertyKey();
        /** @return VALUE或四种无值状态 */ String state();
        /** 映射属性当前值状态，非VALUE分支不会输出业务事实字段。 */
        private static Value from(RuntimeDeviceCurrentResult.PropertyValue source) {
            if (source.state() == RuntimeDeviceCurrentResult.State.VALUE) {
                return new AvailableValue(source.propertyKey(), source.state().name(), source.value(),
                        source.occurredAt(), source.reportedModelVersionId());
            }
            return new EmptyValue(source.propertyKey(), source.state().name());
        }
    }

    /**
     * @param propertyKey 属性键 @param state 固定VALUE @param value 已验业务值
     * @param occurredAt 采集时刻 @param reportedModelVersionId 来源模型
     */
    @Schema(name = "DashboardShareAvailableCurrentProperty")
    public record AvailableValue(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String propertyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "VALUE") String state,
            @Schema(implementation = Object.class, types = {"number", "string", "boolean", "object", "array"},
                    requiredMode = Schema.RequiredMode.REQUIRED) JsonNode value,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportedModelVersionId) implements Value {
        /** 深复制JSON值，防止序列化期间跨层修改。 */
        public AvailableValue { value = value.deepCopy(); }
        /** @return JSON值副本 */
        @Override public JsonNode value() { return value.deepCopy(); }
    }

    /** @param propertyKey 属性键 @param state 四种无值状态之一 */
    @Schema(name = "DashboardShareEmptyCurrentProperty")
    public record EmptyValue(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String propertyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NO_VALUE", "SOURCE_VERSION_UNKNOWN", "SOURCE_MODEL_MISMATCH", "CONTRACT_MISMATCH"})
            String state) implements Value { }

    /** @param items 目录项 @param nextCursor 下一页游标 @param hasMore 是否有下一页 */
    @Schema(name = "DashboardShareDeviceCatalogResponse")
    public record Catalog(
            @NotNull @ArraySchema(maxItems = 50, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<CatalogItem> items,
            @Schema(types = {"string", "null"}, maxLength = 2048,
                    requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
        /** 映射目录页且不暴露内部排序时刻。 */
        public static Catalog from(DashboardShareCatalogPage source) {
            return new Catalog(source.items().stream().map(CatalogItem::from).toList(),
                    source.nextCursor(), source.hasMore());
        }
    }

    /** @param deviceId 设备ID @param name 名称 @param deviceStatus 状态 @param currentModelVersionId 当前模型 */
    @Schema(name = "DashboardShareDeviceCatalogItem")
    public record CatalogItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"INACTIVE", "ONLINE", "OFFLINE"}) String deviceStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID currentModelVersionId) {
        /** 去掉仅供keyset的createdAt。 */
        private static CatalogItem from(RuntimeDeviceCatalogItem source) {
            return new CatalogItem(source.deviceId(), source.name(), source.deviceStatus(), source.currentModelVersionId());
        }
    }

    /**
     * 完整版本化历史。
     * @param from 由冻结preset与本轮anchor派生的开始时刻
     * @param to 本轮anchor结束时刻
     * @param requestedGranularity 请求粒度 @param actualGranularity 实际粒度
     * @param aggregation 聚合 @param points 历史点
     */
    @Schema(name = "DashboardShareVersionedHistoryResponse")
    public record History(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant from,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"}) String requestedGranularity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"}) String actualGranularity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"AVG", "MIN", "MAX", "SUM", "COUNT"}) String aggregation,
            @NotNull @ArraySchema(maxItems = 2000, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<HistoryPoint> points) {
        /** 映射telemetry版本化结果。 */
        public static History from(AppVersionedPropertyHistory source, Instant from, Instant to) {
            return new History(from, to, source.requestedGranularity(), source.actualGranularity(), source.aggregation(),
                    source.points().stream().map(HistoryPoint::from).toList());
        }
    }

    /**
     * 一个版本化历史点。
     * @param ts 桶或原始时刻 @param value 值 @param sampleCount 样本数
     * @param thingModelVersionId 来源版本；LEGACY时允许null @param modelVersion 模型版本标签
     */
    @Schema(name = "DashboardShareVersionedHistoryPoint")
    public record HistoryPoint(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant ts,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double value,
            @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) long sampleCount,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID thingModelVersionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelVersion) {
        /** 映射历史点。 */
        private static HistoryPoint from(AppVersionedHistoryPoint source) {
            return new HistoryPoint(source.ts(), source.value(), source.sampleCount(),
                    source.thingModelVersionId(), source.modelVersion());
        }
    }

    /** @param items 告警项 @param nextCursor 下一页游标 @param hasMore 是否有下一页 */
    @Schema(name = "DashboardShareAlarmQueryResponse")
    public record AlarmPage(
            @NotNull @ArraySchema(maxItems = 50, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<AlarmItem> items,
            @Schema(types = {"string", "null"}, maxLength = 2048,
                    requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
        /** 映射告警页。 */
        public static AlarmPage from(DashboardShareAlarmPage source) {
            return new AlarmPage(source.items().stream().map(AlarmItem::from).toList(),
                    source.nextCursor(), source.hasMore());
        }
    }

    /**
     * 告警实例公开闭集。
     * @param id 告警ID @param deviceId 设备ID @param alarmType 类型 @param severity 等级
     * @param conditionState 条件状态 @param ackState 确认状态 @param firstConditionAt 首次命中
     * @param activatedAt 激活时刻 @param clearedAt 清除时刻 @param acknowledgedAt 确认时刻
     * @param lastReceivedAt 最近接收时刻 @param version CAS版本
     */
    @Schema(name = "DashboardShareAlarmItemResponse")
    public record AlarmItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String alarmType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"}) String severity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "ACTIVE", "CLEARED"}) String conditionState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"UNACKNOWLEDGED", "ACKNOWLEDGED"}) String ackState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant firstConditionAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant activatedAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant clearedAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant acknowledgedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant lastReceivedAt,
            @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) int version) {
        /** 映射告警公开字段，不输出确认账号或规则正文。 */
        private static AlarmItem from(AlarmDeviceQueryItem source) {
            return new AlarmItem(source.id(), source.deviceId(), source.alarmType(), source.severity(),
                    source.conditionState(), source.ackState(), source.firstConditionAt(), source.activatedAt(),
                    source.clearedAt(), source.acknowledgedAt(), source.lastReceivedAt(), source.version());
        }
    }
}
