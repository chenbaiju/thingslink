package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;

/** 出口单元测试显式许可；生产许可只由真实事务服务签发。 */
final class MqttRouteFixtures {
    /** 禁止实例化测试工厂。 */ private MqttRouteFixtures() { }
    /** 命令以实际连接设备而非子设备作为命名空间。 */
    static DeviceMqttDownlinkRoute route(DeviceCommandDispatch value) {
        return new DeviceMqttDownlinkRoute(value.tenantId(), value.projectId(), value.connectionDeviceId(), 7,
                value.projectKey(), value.connectionDeviceKey());
    }
    /** 拓扑回执网关许可。 */
    static DeviceMqttDownlinkRoute route(TopologyReplyMessage value) {
        return new DeviceMqttDownlinkRoute(value.tenantId(), value.projectId(), value.gatewayId(), 7,
                value.projectKey(), value.gatewayKey());
    }
    /** 配置下发网关许可。 */
    static DeviceMqttDownlinkRoute route(DeviceConfigPush value) {
        return new DeviceMqttDownlinkRoute(value.tenantId(), value.projectId(), value.gatewayId(), 7,
                value.projectKey(), value.gatewayKey());
    }
    /** Modbus读取网关许可。 */
    static DeviceMqttDownlinkRoute route(ModbusRequest value) {
        return new DeviceMqttDownlinkRoute(value.tenantId(), value.projectId(), value.gatewayId(), 7,
                value.projectKey(), value.gatewayKey());
    }
}
