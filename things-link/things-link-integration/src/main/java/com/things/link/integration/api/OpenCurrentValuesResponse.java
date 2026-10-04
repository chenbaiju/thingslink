package com.things.link.integration.api;

import com.things.link.device.application.RuntimeDeviceCurrentResult;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param devices 按请求设备顺序的当前值状态 */
public record OpenCurrentValuesResponse(
        @NotNull @ArraySchema(minItems = 1, maxItems = 20,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<DeviceItem> devices) {

    /** @param source application运行结果 @return HTTP闭集投影 */
    public static OpenCurrentValuesResponse from(RuntimeDeviceCurrentResult source) {
        return new OpenCurrentValuesResponse(source.devices().stream().map(DeviceItem::from).toList());
    }

    /** @param deviceId 设备 @param status 可用性 @param values 可用时按请求键完整返回 */
    @Schema(name = "OpenDeviceCurrentValueDeviceItem")
    public record DeviceItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NOT_AVAILABLE", "MODEL_MISMATCH", "AVAILABLE"}) String status,
            @NotNull @ArraySchema(maxItems = 50,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ValueItem> values) {

        /** @param source application设备结果 @return HTTP设备项 */
        private static DeviceItem from(RuntimeDeviceCurrentResult.DeviceValues source) {
            return new DeviceItem(source.deviceId(), source.status().name(),
                    source.values().stream().map(ValueItem::from).toList());
        }
    }

    /** 当前值两种闭集分支；非VALUE不得在机器合同中出现可选业务事实。 */
    @Schema(name = "OpenDeviceCurrentValueItem", oneOf = {AvailableValueItem.class, EmptyValueItem.class})
    public sealed interface ValueItem permits AvailableValueItem, EmptyValueItem {
        /** @return 请求属性键 */ String propertyKey();
        /** @return VALUE或四种无值状态 */ String state();

        /** @param source application属性结果 @return 对应闭集HTTP属性项 */
        private static ValueItem from(RuntimeDeviceCurrentResult.PropertyValue source) {
            if (source.state() == RuntimeDeviceCurrentResult.State.VALUE) {
                return new AvailableValueItem(source.propertyKey(), source.state().name(), source.value(),
                        source.occurredAt(), source.reportedModelVersionId());
            }
            return new EmptyValueItem(source.propertyKey(), source.state().name());
        }
    }

    /** @param propertyKey 属性键 @param state 固定VALUE @param value 已验值 @param occurredAt 采集时刻 @param reportedModelVersionId 来源版本 */
    @Schema(name = "OpenDeviceCurrentAvailableValueItem")
    public record AvailableValueItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String propertyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "VALUE") String state,
            @Schema(implementation = Object.class,
                    types = {"number", "string", "boolean", "object", "array"},
                    requiredMode = Schema.RequiredMode.REQUIRED) JsonNode value,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant occurredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID reportedModelVersionId)
            implements ValueItem { }

    /** @param propertyKey 属性键 @param state 四种无值状态之一 */
    @Schema(name = "OpenDeviceCurrentEmptyValueItem")
    public record EmptyValueItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String propertyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NO_VALUE", "SOURCE_VERSION_UNKNOWN",
                            "SOURCE_MODEL_MISMATCH", "CONTRACT_MISMATCH"}) String state) implements ValueItem { }
}
