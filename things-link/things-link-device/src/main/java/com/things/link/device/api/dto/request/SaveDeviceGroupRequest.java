package com.things.link.device.api.dto.request;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 创建或修改设备组的受控请求，动态条件不能携带任意表达式。 */
public record SaveDeviceGroupRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @NotNull DeviceGroup.Type type,
        @Valid Rule rule) {

    /** 动态设备组条件白名单。 */
    public record Rule(
            @Size(max = 100) Set<UUID> deviceTypeIds,
            @Size(max = 4) Set<Device.Status> statuses,
            @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 128) String> tags,
            DeviceGroupRule.TagMatch tagMatch) {

        /** @return 领域规则；构造器会继续执行跨字段完整性校验 */
        public DeviceGroupRule toDomain() {
            return new DeviceGroupRule(deviceTypeIds, statuses, tags, tagMatch);
        }
    }
}
