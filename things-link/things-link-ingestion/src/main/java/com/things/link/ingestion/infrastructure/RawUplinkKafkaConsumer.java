package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.ConfigReplyUplinkNormalizer;
import com.things.link.ingestion.application.GatewayBatchMessageNormalizer;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.ModbusResponseUplinkNormalizer;
import com.things.link.ingestion.application.RawUplinkMessageNormalizer;
import com.things.link.ingestion.application.EventUplinkMessageNormalizer;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.ingestion.application.TopologyUplinkMessageNormalizer;
import com.things.link.shared.message.DeviceConfigReply;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.ingestion.application.OtaReportUplinkHandler;
import com.things.link.ingestion.application.OtaDownloadRequestUplinkHandler;
import com.things.link.ingestion.application.OtaProgressUplinkHandler;
import com.things.link.ingestion.application.OtaHealthUplinkHandler;
import com.things.link.ingestion.application.OtaCommitReceiptUplinkHandler;
import com.things.link.ingestion.application.OtaCommitReconciliationUplinkHandler;
import com.things.link.ingestion.application.OtaRollbackPreflightUplinkHandler;
import com.things.link.ingestion.application.OtaRollbackUplinkHandler;
import com.things.link.ingestion.application.OtaInstallStopUplinkHandler;
import com.things.link.shared.message.StandardUplinkMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 消费原始 MQTT 上行消息并发布协议无关标准信封。
 *
 * <p>标准消息的 broker 确认完成后方法才返回，配合 {@code RECORD} ack 保证不会先提交 raw offset
 * 再丢失 normalized 消息。这里仍是 at-least-once，进程在发布成功与 offset 提交之间退出会产生重复，
 * 后续切片必须使用 messageId inbox 完成端到端幂等。</p>
 */
public class RawUplinkKafkaConsumer {

    /** 只记录标识与链路信息，禁止把设备原始载荷写入日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(RawUplinkKafkaConsumer.class);

    /** 原始上行主题，与 deploy/redpanda/init-topics.sh 的 12 分区基线一致。 */
    public static final String RAW_UPLINK_TOPIC = "tc.device.uplink.raw";

    /** 标准上行主题，继续以 deviceId 为 key 保证同设备有序。 */
    public static final String NORMALIZED_UPLINK_TOPIC = "tc.device.uplink.normalized";

    /** 独立事件事实主题，不进入属性规则或processed链。 */
    public static final String EVENT_NORMALIZED_TOPIC = "tc.device.event.normalized";

    /** 拓扑消息主题，以网关 ID 为 key 保证同网关拓扑/在线态消息有序。 */
    public static final String TOPO_TOPIC = "tc.device.topo";

    /** 网关批量属性上报主题，以网关 ID 为 key，拆分后按子设备 ID 重发到 normalized。 */
    public static final String BATCH_TOPIC = "tc.device.batch";

    /** 网关配置回执主题，以网关 ID 为 key。 */
    public static final String CONFIG_REPLY_TOPIC = "tc.device.config.reply";

    /** 网关 Modbus 响应主题，以网关 ID 为 key。 */
    public static final String MODBUS_RESPONSE_TOPIC = "tc.device.modbus.response";

    /** 等待 broker 确认的上限，避免 listener 线程在网络故障时无限占用。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** 设备报文解析与信封转换器。 */
    private final RawUplinkMessageNormalizer normalizer;

    /** 生产装配的严格MQTT事件标准化器，禁止缺省旁路。 */
    private final EventUplinkMessageNormalizer eventNormalizer;

    /** 拓扑报文解析器；非拓扑类型返回空。 */
    private final TopologyUplinkMessageNormalizer topologyNormalizer;

    /** 批量上报报文解析器；非批量类型返回空。 */
    private final GatewayBatchMessageNormalizer batchNormalizer;

    /** 配置回执解析器；非配置回执类型返回空。 */
    private final ConfigReplyUplinkNormalizer configReplyNormalizer;

    /** Modbus 响应解析器；非 Modbus 响应类型返回空。 */
    private final ModbusResponseUplinkNormalizer modbusResponseNormalizer;

    /** OTA报告直接提交其本域事务，不能作为遥测属性报文标准化。 */
    private final OtaReportUplinkHandler otaReports;
    /** MQTT下载申请独立持久分流。 */
    private final OtaDownloadRequestUplinkHandler otaDownloads;
    /** 认证OTA进度独立持久分流。 */
    private final OtaProgressUplinkHandler otaProgress;
    /** 独立健康上行分流。 */
    private final OtaHealthUplinkHandler otaHealth;
    /** 独立提交回执上行分流。 */
    private final OtaCommitReceiptUplinkHandler otaCommitReceipts;
    /** 独立提交对账响应分流。 */
    private final OtaCommitReconciliationUplinkHandler otaReconciliation;
    /** 独立只读回退预检分流。 */
    private final OtaRollbackPreflightUplinkHandler otaPreflight;
    /** 原子回退与独立状态报告精确路由。 */
    private final OtaRollbackUplinkHandler otaRollback;
    /** 安装前原子停止与状态观察入口。 */
    private final OtaInstallStopUplinkHandler otaInstallStop;

    /** 继承 support 模块 追踪生产者拦截器 的 Kafka 模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 创建原始上行消费者。
     *
     * @param normalizer 原始消息标准化器
     * @param eventNormalizer 独立事件标准化器
     * @param topologyNormalizer 拓扑消息标准化器
     * @param batchNormalizer 批量上报标准化器
     * @param configReplyNormalizer 配置回执标准化器
     * @param modbusResponseNormalizer Modbus 响应标准化器
     * @param kafkaTemplate 标准消息发布模板
     */
    public RawUplinkKafkaConsumer(
            RawUplinkMessageNormalizer normalizer,
            EventUplinkMessageNormalizer eventNormalizer,
            TopologyUplinkMessageNormalizer topologyNormalizer,
            GatewayBatchMessageNormalizer batchNormalizer,
            ConfigReplyUplinkNormalizer configReplyNormalizer,
            ModbusResponseUplinkNormalizer modbusResponseNormalizer,
            KafkaTemplate<String, Object> kafkaTemplate, OtaReportUplinkHandler otaReports,
            OtaDownloadRequestUplinkHandler otaDownloads, OtaProgressUplinkHandler otaProgress,
            OtaHealthUplinkHandler otaHealth, OtaCommitReceiptUplinkHandler otaCommitReceipts,
            OtaCommitReconciliationUplinkHandler otaReconciliation, OtaRollbackPreflightUplinkHandler otaPreflight, OtaRollbackUplinkHandler otaRollback, OtaInstallStopUplinkHandler otaInstallStop) {
        this.otaInstallStop = java.util.Objects.requireNonNull(otaInstallStop, "otaInstallStop");
        this.otaRollback = java.util.Objects.requireNonNull(otaRollback, "otaRollback");
        this.otaPreflight = java.util.Objects.requireNonNull(otaPreflight, "otaPreflight");
        this.otaReconciliation = java.util.Objects.requireNonNull(otaReconciliation, "otaReconciliation");
        this.otaHealth = java.util.Objects.requireNonNull(otaHealth, "otaHealth");
        this.otaCommitReceipts = java.util.Objects.requireNonNull(otaCommitReceipts, "otaCommitReceipts");
        this.otaProgress = java.util.Objects.requireNonNull(otaProgress, "otaProgress");
        this.otaDownloads = java.util.Objects.requireNonNull(otaDownloads, "otaDownloads");
        this.otaReports = java.util.Objects.requireNonNull(otaReports, "otaReports");
        this.normalizer = normalizer;
        this.eventNormalizer = java.util.Objects.requireNonNull(eventNormalizer, "eventNormalizer");
        this.topologyNormalizer = topologyNormalizer;
        this.batchNormalizer = batchNormalizer;
        this.configReplyNormalizer = configReplyNormalizer;
        this.modbusResponseNormalizer = modbusResponseNormalizer;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 校验分区键、转换报文并同步确认标准消息。
     *
     * @param record 原始上行 Kafka 记录
     */
//       示例：@KafkaListener(topics = RAW_UPLINK_TOPIC)
    @KafkaListener(topics = RAW_UPLINK_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-raw",
            containerFactory = "dataKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-raw:4}")
    public void consume(ConsumerRecord<String, RawUplinkMessage> record) {
        RawUplinkMessage rawUplinkMessage = record.value();
        if (rawUplinkMessage == null) {
            // raw 主题不是 compact topic，空值没有删除语义；重试无法恢复，必须作为协议错误留档。
            throw new InvalidUplinkMessageException("原始上行信封不能为空");
        }
        String expectedKey = rawUplinkMessage.deviceId().toString();
        if (!expectedKey.equals(record.key())) {
            // 错误 key 会破坏同设备顺序性；不能悄悄改正后继续，否则生产端契约回归将长期不可见。
            throw new InvalidUplinkMessageException("Kafka key 必须等于原始信封 deviceId");
        }

        if (otaReports.tryAccept(rawUplinkMessage)) return;
        if (otaDownloads.tryAccept(rawUplinkMessage)) return;
        if (otaProgress.tryAccept(rawUplinkMessage)) return;
        if (otaHealth.tryAccept(rawUplinkMessage)) return;
        if (otaCommitReceipts.tryAccept(rawUplinkMessage)) return;
        if (otaReconciliation.tryAccept(rawUplinkMessage)) return;
        if (otaPreflight.tryAccept(rawUplinkMessage)) return;
        if (otaRollback.tryAccept(rawUplinkMessage)) return;
        if (otaInstallStop.tryAccept(rawUplinkMessage)) return;

        Optional<EventUplinkMessage> event = eventNormalizer.tryNormalize(rawUplinkMessage);
        if (event.isPresent()) {
            publish(EVENT_NORMALIZED_TOPIC, expectedKey, event.orElseThrow());
            return;
        }

        // 拓扑消息在 raw 层分流到独立主题，避免污染 telemetry 的 normalized 管道。
        Optional<DeviceTopologyMessage> topology = topologyNormalizer.tryNormalize(rawUplinkMessage);
        if (topology.isPresent()) {
            publish(TOPO_TOPIC, expectedKey, topology.orElseThrow());
            return;
        }

        // 批量属性上报同样在 raw 层分流到独立主题，交由 device 模块按 dev_topo 复核归属后再拆到 normalized。
        Optional<GatewayBatchMessage> batch = batchNormalizer.tryNormalize(rawUplinkMessage);
        if (batch.isPresent()) {
            publish(BATCH_TOPIC, expectedKey, batch.orElseThrow());
            return;
        }

        // 配置回执在 raw 层分流到独立主题，交由 device 模块做版本诊断与落后重推。
        Optional<DeviceConfigReply> configReply = configReplyNormalizer.tryNormalize(rawUplinkMessage);
        if (configReply.isPresent()) {
            publish(CONFIG_REPLY_TOPIC, expectedKey, configReply.orElseThrow());
            return;
        }

        // Modbus 响应在 raw 层分流到独立主题，交由 device 模块按 requestId 关联并解码到 normalized。
        Optional<ModbusResponse> modbusResponse = modbusResponseNormalizer.tryNormalize(rawUplinkMessage);
        if (modbusResponse.isPresent()) {
            publish(MODBUS_RESPONSE_TOPIC, expectedKey, modbusResponse.orElseThrow());
            return;
        }

        StandardUplinkMessage normalized = normalizer.normalize(rawUplinkMessage);
        publish(NORMALIZED_UPLINK_TOPIC, expectedKey, normalized);
        LOGGER.debug("原始上行已标准化 messageId={} deviceId={} traceId={}",
                normalized.messageId(), normalized.deviceId(), normalized.traceId());
    }

    /** 同步确认 broker 后才返回，配合 RECORD ack 保证不会先提交 raw offset 再丢失下游消息。 */
    private void publish(String topic, String key, Object message) {
        try {
            kafkaTemplate.send(topic, key, message).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待下游消息发布确认时线程被中断", exception);
        } catch (Exception exception) {
            // broker/网络错误具备恢复可能，保留普通异常类型以进入有限重试而非直接 DLQ。
            throw new IllegalStateException("下游消息发布失败", exception);
        }
    }
}
