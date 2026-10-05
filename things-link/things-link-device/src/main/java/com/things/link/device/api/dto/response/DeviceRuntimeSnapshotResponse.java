package com.things.link.device.api.dto.response;

import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceSnapshotResult;
import com.things.link.device.application.RuntimeModelDescription;
import com.things.link.device.application.RuntimePropertyDescription;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param devices 按请求顺序的设备状态 @param models 成功设备实际使用的模型描述 */
public record DeviceRuntimeSnapshotResponse(
        @NotNull @ArraySchema(minItems = 1, maxItems = 20,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<DeviceItem> devices,
        @NotNull @ArraySchema(maxItems = 20,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<ModelItem> models) {

    /** @param source application运行结果 @return HTTP闭集投影 */
    public static DeviceRuntimeSnapshotResponse from(RuntimeDeviceSnapshotResult source) {
        return new DeviceRuntimeSnapshotResponse(source.devices().stream().map(DeviceItem::from).toList(),
                source.models().stream().map(ModelItem::from).toList());
    }

    /** 三种设备响应分支共用的稳定身份。 */
    @Schema(name = "ConsoleDeviceSnapshotDeviceItem",
            oneOf = {NotAvailableDeviceItem.class, ModelMismatchDeviceItem.class, AvailableDeviceItem.class})
    public sealed interface DeviceItem permits NotAvailableDeviceItem, ModelMismatchDeviceItem, AvailableDeviceItem {
        /** @param source 设备三态 @return 对应闭集HTTP设备项 */
        static DeviceItem from(RuntimeDeviceAvailability source) {
            return switch (source.status()) {
                case NOT_AVAILABLE -> new NotAvailableDeviceItem(source.deviceId(), source.status().name());
                case MODEL_MISMATCH -> new ModelMismatchDeviceItem(
                        source.deviceId(), source.status().name(), source.currentModelVersionId());
                case AVAILABLE -> new AvailableDeviceItem(source.deviceId(), source.status().name(),
                        source.name(), source.deviceStatus(), source.lastOnlineAt(), source.currentModelVersionId());
            };
        }
    }

    /** @param deviceId 请求设备 @param status 固定NOT_AVAILABLE */
    @Schema(name = "ConsoleDeviceSnapshotNotAvailableDeviceItem")
    public record NotAvailableDeviceItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "NOT_AVAILABLE") String status)
            implements DeviceItem { }

    /** @param deviceId 请求设备 @param status 固定MODEL_MISMATCH @param currentModelVersionId 当前模型或null */
    @Schema(name = "ConsoleDeviceSnapshotModelMismatchDeviceItem")
    public record ModelMismatchDeviceItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "MODEL_MISMATCH") String status,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID currentModelVersionId) implements DeviceItem { }

    /** @param deviceId 请求设备 @param status 固定AVAILABLE @param name 名称 @param deviceStatus 连接状态 @param lastOnlineAt 最近上线或null @param currentModelVersionId 当前模型 */
    @Schema(name = "ConsoleDeviceSnapshotAvailableDeviceItem")
    public record AvailableDeviceItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "AVAILABLE") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"INACTIVE", "ONLINE", "OFFLINE"}) String deviceStatus,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant lastOnlineAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID currentModelVersionId) implements DeviceItem { }

    /** @param versionId 模型版本 @param digestAlgorithm 摘要算法 @param digest 完整摘要 @param profile 模型运行配置档案 @param properties 属性描述 */
    @Schema(name = "ConsoleDeviceSnapshotModelItem")
    public record ModelItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID versionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = "PG_JSONB_TEXT_V1_SHA256") String digestAlgorithm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String digest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = "TC_PROPERTY_COMPOSITE_V1") String profile,
            @NotNull @ArraySchema(maxItems = 200,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PropertyItem> properties) {

        /** @param source 模型运行描述 @return HTTP模型项 */
        private static ModelItem from(RuntimeModelDescription source) {
            return new ModelItem(source.versionId(), source.digestAlgorithm(), source.digest(), source.profile(),
                    source.properties().stream().map(PropertyItem::from).toList());
        }
    }

    /** 所有属性元数据键恒存在；合同允许的空字段以JSON null表达。 */
    @Schema(name = "ConsoleDeviceSnapshotPropertyItem")
    public record PropertyItem(
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

        /** @param source 属性运行描述 @return HTTP属性项 */
        private static PropertyItem from(RuntimePropertyDescription source) {
            return new PropertyItem(source.propertyKey(), source.dataType(), source.unit(), source.minimumValue(),
                    source.maximumValue(), source.enumOptions(), source.onLabel(), source.offLabel());
        }
    }
}
