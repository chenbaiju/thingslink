package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.InvalidCommandDispatchException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/** ADR0143：广播可见性不等于交付权；只允许权威会话所属实例进入规则与TCP写入。 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
@ConditionalOnProperty(prefix = "things-link.access.tcp", name = "enabled", havingValue = "true")
public class TcpCommandDownlinkKafkaConsumer {
    /** 设备配置与会话归属的应用端口。 */ private final DeviceAccessSessionPort sessions;
    /** 唯一TCP传输出口，绝不注入MQTT发布端口。 */ private final DeviceAccessPushPort push;
    /** 准入后才运行规则。 */ private final DownlinkPreprocessingChain preprocessing;
    /** 唯一命令状态机。 */ private final DeviceCommandService commands;
    /** 低基数结果计数。 */ private final MeterRegistry meters;

    /** 完整生产依赖，无离线或数据库故障的成功替身。 */
    public TcpCommandDownlinkKafkaConsumer(DeviceAccessSessionPort sessions, DeviceAccessPushPort push,
            DownlinkPreprocessingChain preprocessing, DeviceCommandService commands, MeterRegistry meters) {
        this.sessions = sessions; this.push = push; this.preprocessing = preprocessing;
        this.commands = commands; this.meters = meters;
    }

    /** 每个运行身份独立读取原Topic，旧attempt/终态由原准入吸收。 */
    @KafkaListener(id = TcpDownlinkReadiness.LISTENER_ID, topics = DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC,
            groupId = "#{@deviceAccessTcpRuntime.groupId()}", containerFactory = "tcpDownlinkKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.tcp-downlink:1}")
    public void consume(ConsumerRecord<String, DeviceCommandDispatch> record) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TCP下行不能加入调用方事务");
        DeviceCommandDispatch source = record.value();
        if (source == null || source.connectionDeviceId() == null
                || !source.connectionDeviceId().toString().equals(record.key())) {
            throw new InvalidDownlinkMessageException("Kafka key 必须等于实际连接设备 ID");
        }
        if (!usesTcp(source)) return;
        // 属性设置没有TCP线协议，在任何所有权判断/编码前由原状态机收束。
        if (source.operationType() == DeviceCommandDispatch.OperationType.PROPERTY_SET) {
            try { commands.admitDispatch(source); }
            catch (InvalidCommandDispatchException exception) {
                throw new InvalidDownlinkMessageException("下行属性设置与原持久交付身份不一致", exception);
            }
            return;
        }
        if (!push.owns(asPush(source))) { outcome(DeviceAccessPushPort.PushOutcome.DEFERRED_NOT_OWNER); return; }
        try { if (!commands.admitDispatch(source)) return; }
        catch (InvalidCommandDispatchException exception) {
            throw new InvalidDownlinkMessageException("下行命令与原持久交付身份不一致", exception);
        }
        DeviceCommandDispatch processed = preprocessing.apply(source);
        if (!usesTcp(source)) { outcome(DeviceAccessPushPort.PushOutcome.DEFERRED_NOT_OWNER); return; }
        DeviceAccessPushPort.PushOutcome result = push.push(asPush(processed));
        outcome(result);
        switch (result) {
            case DELIVERED -> commands.markDispatched(source, Instant.now());
            case WRITE_FAILED -> commands.recordDispatchFailure(source, DeviceCommandDispatchFailure.DISPATCH_FAILED, Instant.now());
            case DEFERRED_NOT_OWNER -> { }
        }
    }
    /** 基础设施读取失败直接传播，不能用空配置吞掉。 */
    private boolean usesTcp(DeviceCommandDispatch source) {
        return sessions.config(source.tenantId(), source.projectId(), source.connectionDeviceId())
                .filter(config -> config.enabled() && config.protocol() == TransportProtocol.TCP).isPresent();
    }
    /** 保留原信封身份与业务截止。 */
    private static DeviceAccessPushPort.Push asPush(DeviceCommandDispatch source) {
        return new DeviceAccessPushPort.Push(source.tenantId(), source.projectId(), source.connectionDeviceId(),
                source.commandId(), source.commandKey(), source.inputJson(), source.attemptNo(), source.deadlineAt());
    }
    /** 结果标签为固定枚举，不含设备或命令ID。 */
    private void outcome(DeviceAccessPushPort.PushOutcome result) {
        meters.counter("device.access.tcp.downlink", "result", result.name()).increment();
    }
}
