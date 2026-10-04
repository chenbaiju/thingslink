package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.CommandDispatchException;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.InvalidCommandDispatchException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * 把事务 Outbox 发布的命令信封送入 EMQX，并在 Broker 接受后推进命令事实。
 *
 * <p>Kafka 与 EMQX 无法组成一个分布式事务：若进程在 EMQX 成功后、数据库状态提交前退出，记录会重放并
 * 再次发布。因此设备必须按稳定 commandId 去重；消费者不能用“先写 inbox”换取表面 exactly-once，后者会在
 * 写 inbox 后、发布前崩溃时永久丢命令。</p>
 */
public class DeviceCommandDownlinkKafkaConsumer {

    /** 只记录 ID 与次数，不打印可能含设备隐私的命令 input。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceCommandDownlinkKafkaConsumer.class);
    /** 下行主题由 ADR 0021 固定并由部署脚本显式创建。 */
    public static final String DOWNLINK_TOPIC = "tc.device.downlink";

    /** 生效配置通过device application读取，基础设施异常保留为Kafka重试。 */
    private final DeviceAccessSessionPort sessions;

    /** 协议编码前规则空链。 */
    private final DownlinkPreprocessingChain preprocessingChain;
    /** EMQX 发布端口。 */
    private final CommandDownlinkPublisher publisher;
    /** telemetry application 状态推进端口。 */
    private final DeviceCommandService commandService;
    /** 同事务冻结原许可和当前路由。 */
    private final MqttDownlinkAdmissionService admission;

    /**
     * @param preprocessingChain 协议编码前预处理链
     * @param publisher EMQX 发布端口
     * @param commandService 命令状态机公共入口
     */
    public DeviceCommandDownlinkKafkaConsumer(DownlinkPreprocessingChain preprocessingChain,
                                               CommandDownlinkPublisher publisher,
                                               DeviceCommandService commandService, DeviceAccessSessionPort sessions,
                                               MqttDownlinkAdmissionService admission) {
        this.admission = admission;
        this.sessions = sessions;
        this.preprocessingChain = preprocessingChain;
        this.publisher = publisher;
        this.commandService = commandService;
    }

    /**
     * 校验连接设备分区键，发布 MQTT 后推进 DISPATCHED。
     *
     * @param record Outbox 发布的下行 Kafka 记录
     */
    @KafkaListener(id = "sharedCommandDownlink", topics = DOWNLINK_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-downlink",
            containerFactory = "externalKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-downlink:4}")
    public void consume(ConsumerRecord<String, DeviceCommandDispatch> record) {
        // ADR0070决策3：准入必须先提交再外发；加入外层事务会让SHARE跨越网络且允许回滚后已发送。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("命令下行消费不能加入调用方事务");
        }
        DeviceCommandDispatch source = record.value();
        if (source == null || source.connectionDeviceId() == null) {
            throw new InvalidDownlinkMessageException("下行命令信封及连接设备不能为空");
        }
        if (!source.connectionDeviceId().toString().equals(record.key())) {
            throw new InvalidDownlinkMessageException("Kafka key 必须等于实际连接设备 ID");
        }
        if (source.operationType() != DeviceCommandDispatch.OperationType.PROPERTY_SET && !usesMqtt(source)) return;
        // telemetry负责原事实及完整信封校验；只有准入短事务提交后才执行可能含用户逻辑的预处理。
        DeviceMqttDownlinkRoute route;
        try {
            var permitted = admission.command(source);
            if (permitted.isEmpty()) return;
            route = permitted.orElseThrow();
        } catch (InvalidCommandDispatchException exception) {
            // 只转换确定的持久信封冲突，SQL/提交失败必须留在原有可恢复Kafka路径。
            throw new InvalidDownlinkMessageException("下行命令与原持久交付身份不一致", exception);
        }
        DeviceCommandDispatch processed = preprocessingChain.apply(source);
        try {
            Instant publishedAt = publisher.publish(processed, route);
            boolean changed = commandService.markDispatched(source, publishedAt);
            LOGGER.debug("命令下行已由 Broker 接受 commandId={} attemptNo={} changed={}",
                    source.commandId(), source.attemptNo(), changed);
        } catch (CommandDispatchException exception) {
            // D-037：发布失败已由 telemetry 状态机落档（attempt FAILED + 退避或终态），不再抛回 Kafka 叠加重试；
            // 若 recordDispatchFailure 自身的数据库事务失败，异常会穿透本 catch 抛回 Kafka，保护事实落盘。
            commandService.recordDispatchFailure(source, exception.failure(), Instant.now());
            LOGGER.debug("命令下行派发失败已记录 commandId={} attemptNo={} failure={}",
                    source.commandId(), source.attemptNo(), exception.failure().code());
        }
    }
    /** 无配置是既有MQTT；HTTP/CoAP/TCP均不进入共享发布分支。 */
    private boolean usesMqtt(DeviceCommandDispatch source) {
        return sessions.config(source.tenantId(), source.projectId(), source.connectionDeviceId())
                .map(config -> config.enabled() && config.protocol() == TransportProtocol.MQTT).orElse(true);
    }

}
