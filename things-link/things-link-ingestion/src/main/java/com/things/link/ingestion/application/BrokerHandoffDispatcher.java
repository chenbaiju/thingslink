package com.things.link.ingestion.application;

import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration;
import com.things.link.ingestion.infrastructure.BrokerHandoffQualificationRecorder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** 将已持久投递的 Broker 信封分派到 raw Kafka 或命令回复事务，并封闭 poison 信封。 */
@Service
public class BrokerHandoffDispatcher {

    /** poison DLQ 必须在 MQTT ACK 前获得 broker 确认，等待上限沿用 raw handoff 的 2 秒边界。 */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(2);
    /** 命令回复 Topic 前缀。 */
    private static final String COMMAND_PREFIX = "command/";
    /** 命令回复 Topic 后缀。 */
    private static final String REPLY_SUFFIX = "/reply";
    /** 固定属性设置回复 Topic。 */
    private static final String PROPERTY_SET_REPLY = "property/set/reply";
    /** 信封严格解析器。 */
    private final BrokerHandoffEnvelopeParser parser;
    /** 普通上行接管服务。 */
    private final RawUplinkIngestionService rawService;
    /** 命令回复事务服务。 */
    private final CommandReplyIngestionService commandReplyService;
    /** poison 持久化使用共享幂等 Kafka producer。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** 仅资格环境启用的同步脱敏证据记录器。 */
    private final BrokerHandoffQualificationRecorder qualificationRecorder;

    /**
     * @param parser 信封解析器
     * @param rawService 普通上行服务
     * @param commandReplyService 命令回复服务
     * @param kafkaTemplate Kafka 模板
     * @param qualificationRecorder 默认关闭的资格证据记录器
     */
    public BrokerHandoffDispatcher(BrokerHandoffEnvelopeParser parser,
                                   RawUplinkIngestionService rawService,
                                   CommandReplyIngestionService commandReplyService,
                                   KafkaTemplate<String, Object> kafkaTemplate,
                                   BrokerHandoffQualificationRecorder qualificationRecorder) {
        this.parser = parser;
        this.rawService = rawService;
        this.commandReplyService = commandReplyService;
        this.kafkaTemplate = kafkaTemplate;
        this.qualificationRecorder = qualificationRecorder;
    }

    /**
     * 同步完成一次持久交接；任何依赖异常都向 MQTT callback 冒泡以保留未确认消息。
     *
     * @param bytes 内部 Topic payload
     * @return 获得下游持久事实后可安全 ACK 的分类
     */
    public HandoffDisposition dispatch(byte[] bytes) {
        BrokerHandoffEnvelope envelope = parser.parse(bytes).orElse(null);
        if (envelope == null) {
            HandoffDisposition disposition = quarantine(bytes);
            qualificationRecorder.recordPoison(bytes, disposition);
            return disposition;
        }
        EmqxMessagePublishedRequest request = new EmqxMessagePublishedRequest(envelope.username(), envelope.topic(),
                envelope.payloadBase64(), envelope.qos(), envelope.retained(), envelope.clientId(),
                envelope.publishedAtMs());
        MqttUplinkTopic topic = MqttUplinkTopic.parse(envelope.topic()).orElse(null);
        String dispatchType = topic != null && isCommandReply(topic.messageType()) ? "command-reply" : "raw";
        try {
            HandoffDisposition disposition = "command-reply".equals(dispatchType)
                    ? commandReplyService.ingestHandoff(request) : rawService.ingestHandoff(request, envelope.authenticatedIdentity());
            qualificationRecorder.record(envelope, dispatchType, disposition);
            return disposition;
        } catch (RuntimeException exception) {
            qualificationRecorder.record(envelope, dispatchType, HandoffDisposition.TRANSIENT_RETRY);
            throw exception;
        }
    }

    /** 信封损坏时只保存摘要和固定原因；DLQ ACK 失败必须抛出，不能确认 MQTT poison。 */
    private HandoffDisposition quarantine(byte[] bytes) {
        String digest = sha256(bytes == null ? new byte[0] : bytes);
        BrokerHandoffDeadLetter deadLetter = new BrokerHandoffDeadLetter(
                1, null, "broker_handoff_envelope_invalid", digest, Instant.now());
        try {
            kafkaTemplate.send(UplinkKafkaConfiguration.DEAD_LETTER_TOPIC, digest, deadLetter)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return HandoffDisposition.QUARANTINED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Broker handoff poison 死信确认时被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Broker handoff poison 死信发送失败", exception);
        }
    }

    /** 精确识别两类命令回复，其余合法或未知上行继续进入 raw 统一分派。 */
    private static boolean isCommandReply(String messageType) {
        return PROPERTY_SET_REPLY.equals(messageType)
                || messageType.startsWith(COMMAND_PREFIX) && messageType.endsWith(REPLY_SUFFIX);
    }

    /** 生成固定小写 SHA-256，既可用作 DLQ key 又不会泄露信封明文。 */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
