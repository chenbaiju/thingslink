package com.things.link.device.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** null坐标对显式清除；禁止用旧文本位置推导。 */
@JsonDeserialize(using=UpdateDeviceLocationPointRequest.Deserializer.class)
@Schema(description="设备WGS84当前坐标；经纬度同时为空表示清除")
public record UpdateDeviceLocationPointRequest(
        @Schema(types={"number","null"}, description="经度，-180至180") Double longitude,
        @Schema(types={"number","null"}, description="纬度，-90至90") Double latitude,
        @NotNull @Pattern(regexp="0|[1-9][0-9]{0,18}") @Schema(description="GET返回的十进制版本字符串，避免JS精度丢失") String version) {
    /** 与现有接入配置一致：已知字段严格类型，未知字段沿ADR0042忽略。 */
    public static final class Deserializer extends ValueDeserializer<UpdateDeviceLocationPointRequest> {
        @Override public UpdateDeviceLocationPointRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root=context.readTree(parser);
            if (root == null || !root.isObject() || !root.path("version").isString())
                return context.reportInputMismatch(UpdateDeviceLocationPointRequest.class,"坐标版本必须是十进制字符串");
            return new UpdateDeviceLocationPointRequest(coordinate(root.get("longitude"),context),
                    coordinate(root.get("latitude"),context),root.path("version").asString());
        }
        private static Double coordinate(JsonNode value, DeserializationContext context) {
            if (value == null || value.isNull()) return null;
            if (!value.isNumber()) return context.reportInputMismatch(Double.class,"坐标必须为数值或null");
            return value.doubleValue();
        }
    }
}
