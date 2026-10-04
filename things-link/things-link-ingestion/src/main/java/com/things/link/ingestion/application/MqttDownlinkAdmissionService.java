package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceConfigDeliveryAdmissionService;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.telemetry.application.DeviceCommandService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR0194：同一短事务冻结业务许可和实际MQTT接收者，返回后才允许外部网络。 */
@Service
public class MqttDownlinkAdmissionService {
    /** 项目、设备锁及锁后配置重读。 */
    private final DeviceMqttDownlinkRoutePort routes;
    /** 保留原命令信封和终态合同。 */
    private final DeviceCommandService commands;
    /** 保留原配置Outbox和项目许可合同。 */
    private final DeviceConfigDeliveryAdmissionService configs;

    /** 仅依赖模块公开应用端口，不跨域查询持久表。 */
    public MqttDownlinkAdmissionService(DeviceMqttDownlinkRoutePort routes,
            DeviceCommandService commands, DeviceConfigDeliveryAdmissionService configs) {
        this.routes = routes;
        this.commands = commands;
        this.configs = configs;
    }

    /** 命令原拒绝仍落库，不能因先发现无MQTT路由而跳过必要终态。 */
    @Transactional
    public Optional<DeviceMqttDownlinkRoute> command(DeviceCommandDispatch dispatch) {
        if (dispatch == null) throw new InvalidDownlinkMessageException("命令信封不能为空");
        var route = current(dispatch.tenantId(), dispatch.projectId(), dispatch.connectionDeviceId());
        // 项目/设备先于command行；此调用加入同一事务，提交异常不会返回发送许可。
        if (!commands.admitDispatch(dispatch)) return Optional.empty();
        return route.map(value -> matching(value, dispatch.projectKey(), dispatch.connectionDeviceKey()));
    }

    /** 配置准入保留原不可变Outbox核验，路由缺失不得构造裸Topic。 */
    @Transactional
    public DeviceMqttDownlinkRoute config(DeviceConfigPush push) {
        if (push == null) throw new InvalidDownlinkMessageException("配置信封不能为空");
        var route = current(push.tenantId(), push.projectId(), push.gatewayId());
        configs.admit(push);
        return matching(required(route), push.projectKey(), push.gatewayKey());
    }

    /** 拓扑回执的实际接收者始终为原信封网关。 */
    @Transactional
    public DeviceMqttDownlinkRoute topology(TopologyReplyMessage reply) {
        if (reply == null) throw new InvalidDownlinkMessageException("拓扑回执不能为空");
        return matching(required(current(reply.tenantId(), reply.projectId(), reply.gatewayId())),
                reply.projectKey(), reply.gatewayKey());
    }

    /** Modbus请求只取得当前原网关许可，不随子设备拓扑自动改投。 */
    @Transactional
    public DeviceMqttDownlinkRoute modbus(ModbusRequest request) {
        if (request == null) throw new InvalidDownlinkMessageException("Modbus请求不能为空");
        return matching(required(current(request.tenantId(), request.projectId(), request.gatewayId())),
                request.projectKey(), request.gatewayKey());
    }

    /** 缺失身份在SQL前拒绝；数据库故障原样传播。 */
    private Optional<DeviceMqttDownlinkRoute> current(UUID tenantId, UUID projectId, UUID deviceId) {
        if (tenantId == null || projectId == null || deviceId == null) {
            throw new InvalidDownlinkMessageException("MQTT接收者身份不完整");
        }
        return routes.lockCurrent(tenantId, projectId, deviceId);
    }

    /** 普通下行失去许可沿既有确定性拒绝路径处理。 */
    private static DeviceMqttDownlinkRoute required(Optional<DeviceMqttDownlinkRoute> route) {
        return route.orElseThrow(() -> new InvalidDownlinkMessageException("MQTT接收者当前不可交付"));
    }

    /** 服务器键必须与持久信封一致，不修补信封、改投或隐式升级接收者。 */
    private static DeviceMqttDownlinkRoute matching(DeviceMqttDownlinkRoute route,
            String projectKey, String deviceKey) {
        if (!route.projectKey().equals(projectKey) || !route.deviceKey().equals(deviceKey)) {
            throw new InvalidDownlinkMessageException("MQTT接收者与原信封路由不一致");
        }
        return route;
    }
}
