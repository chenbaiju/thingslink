package com.things.link.device.application;

import com.things.link.device.application.ModbusPollService.ResolvedModbusValue;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.OutboxRouteCatalog;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * ADR0063：以同一数据库事务接管轮询响应，避免完成请求后版本解析或Kafka失败造成交付丢失。
 * 接纳只保证完整Outbox已经提交，broker确认及至少一次重试由共享发布器负责。
 */
@Service
public class ModbusResponseAcceptanceService {

    /** 注入Spring代理，使归属检查、条件完成和解码加入接管事务。 */
    private final ModbusPollService pollService;
    /** 在接纳时冻结权威版本，发布重试不得重新查询。 */
    private final ThingModelVersionBindingService versionBindingService;
    /** MANDATORY追加确保与请求完成使用同一事务连接。 */
    private final TransactionalOutboxRepository outboxRepository;
    /** 序列化失败也必须回滚请求完成。 */
    private final ObjectMapper objectMapper;

    /**
     * @param pollService 轮询状态与解码代理
     * @param versionBindingService 权威当前版本代理
     * @param outboxRepository 同事务持久交付端口
     * @param objectMapper 标准信封序列化器
     */
    public ModbusResponseAcceptanceService(ModbusPollService pollService,
            ThingModelVersionBindingService versionBindingService,
            TransactionalOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.pollService = pollService;
        this.versionBindingService = versionBindingService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * ADR0063决策3：每条响应用独立事务接管，三秒覆盖整个接管事务及CAS参与SQL，
     * 不是含连接借用的精确墙钟上限。
     * 未匹配、ERROR及已完成响应不产生交付；任何后段失败与请求状态一起回滚。
     *
     * @param response 已通过消费信封检查、仍须由引擎核验归属的响应
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void accept(ModbusResponse response) {
        pollService.handleResponse(response).ifPresent(value -> appendNormalized(value, response));
    }

    /** 引擎已用轮询事实建立可信范围并赢得CAS，版本与Outbox沿用同一独立事务连接。 */
    private void appendNormalized(ResolvedModbusValue value, ModbusResponse response) {
        StandardUplinkMessage normalized = new StandardUplinkMessage(
                value.messageId(), response.tenantId(), response.projectId(), value.subDeviceId(), value.gatewayId(),
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                versionBindingService.resolveCurrentVersionWithinEstablishedScope(
                        response.projectId(), value.subDeviceId()),
                response.receivedAt(), response.receivedAt(), response.traceId(),
                0, Map.of(value.propertyKey(), value.value()));
        outboxRepository.append(new OutboxEvent(Uuid7.generate(), response.tenantId(), response.projectId(),
                OutboxRouteCatalog.MODBUS_RESULT_AGGREGATE_TYPE, value.subDeviceId(),
                OutboxRouteCatalog.MODBUS_NORMALIZED_EVENT_TYPE, value.subDeviceId().toString(),
                objectMapper.writeValueAsString(normalized), response.traceId(), Instant.now()));
    }
}
