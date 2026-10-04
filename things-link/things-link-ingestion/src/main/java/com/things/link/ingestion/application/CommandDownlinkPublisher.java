package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;

import java.time.Instant;

/** 平台下行到设备协议出口；application 只依赖该端口，不感知 EMQX HTTP 客户端。 */
public interface CommandDownlinkPublisher {

    /**
     * 以 QoS 1、非 retained 语义把命令交给 Broker。
     *
     * @param dispatch 已完成预处理的派发信封
     * @param route 同一业务许可事务冻结的实际接收者，禁止缺失或重新查询
     * @return Broker 接受时刻；这不是设备 ACK
     */
    Instant publish(DeviceCommandDispatch dispatch, DeviceMqttDownlinkRoute route);

    /**
     * 以 QoS 1、非 retained 语义把拓扑上报回执交给 Broker（{@code down/topo/reply}）。
     *
     * @param reply 已确权的拓扑回执信封
     * @param route 同一业务许可事务冻结的实际接收者，禁止缺失或重新查询
     * @return Broker 接受时刻
     */
    Instant publishTopologyReply(TopologyReplyMessage reply, DeviceMqttDownlinkRoute route);

    /**
     * 以 QoS 1、非 retained 语义把配置下发交给 Broker（{@code down/config}）。
     *
     * @param push 已确权的配置下发信封
     * @param route 同一业务许可事务冻结的实际接收者，禁止缺失或重新查询
     * @return Broker 接受时刻
     */
    Instant publishConfig(DeviceConfigPush push, DeviceMqttDownlinkRoute route);

    /**
     * 以 QoS 1、非 retained 语义把 Modbus 读请求交给 Broker（{@code down/modbus/request}）。
     *
     * @param request 已确权的读请求信封
     * @param route 同一业务许可事务冻结的实际接收者，禁止缺失或重新查询
     * @return Broker 接受时刻
     */
    Instant publishModbusRequest(ModbusRequest request, DeviceMqttDownlinkRoute route);
}
