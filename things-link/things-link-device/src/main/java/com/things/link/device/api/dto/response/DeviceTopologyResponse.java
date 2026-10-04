package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceTopology;

import java.time.Instant;
import java.util.UUID;

/** 拓扑绑定响应。 */
public record DeviceTopologyResponse(UUID id, UUID gatewayDeviceId, UUID subDeviceId,
                                     DeviceTopology.BindSource bindSource,
                                     DeviceTopology.OnlineStatus onlineStatus,
                                     Instant boundAt, Instant unboundAt) {
    /** @param topology 领域对象 @return API 响应 */
    public static DeviceTopologyResponse from(DeviceTopology topology) {
        return new DeviceTopologyResponse(topology.id(), topology.gatewayDeviceId(), topology.subDeviceId(),
                topology.bindSource(), topology.onlineStatus(), topology.boundAt(), topology.unboundAt());
    }
}
