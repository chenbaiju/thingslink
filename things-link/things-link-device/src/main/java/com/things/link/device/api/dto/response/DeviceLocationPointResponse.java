package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceLocationPoint;
import io.swagger.v3.oas.annotations.media.Schema;

/** 当前坐标快照。 */
public record DeviceLocationPointResponse(
        @Schema(types={"number","null"}, requiredMode=Schema.RequiredMode.REQUIRED, description="WGS84经度；未设置时null") Double longitude,
        @Schema(types={"number","null"}, requiredMode=Schema.RequiredMode.REQUIRED, description="WGS84纬度；未设置时null") Double latitude,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String version) {
    public static DeviceLocationPointResponse from(DeviceLocationPoint point) {
        return new DeviceLocationPointResponse(point.longitude(), point.latitude(), Long.toString(point.version()));
    }
}
