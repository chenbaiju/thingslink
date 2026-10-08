package com.things.link.ingestion.application;

import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.TransportProtocol;

import java.time.Duration;
import java.time.DateTimeException;
import java.util.Objects;
import java.util.Optional;

/** 仅把精确七层MQTT事件路径转换到独立信封，保留可信身份与原字节计量。 */
public final class EventUplinkMessageNormalizer {
    /** 默认未来时间窗口沿ADR0235冻结五分钟。 */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    /** 事件正文的独立严格读取器。 */
    private final EventUplinkMessageReader reader;

    /** @param reader 闭合事件正文读取器 */
    public EventUplinkMessageNormalizer(EventUplinkMessageReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    /**
     * @param raw Broker确权后的原始信封
     * @return 非事件为空，精确事件必须严格标准化，否则永久拒绝
     */
    public Optional<EventUplinkMessage> tryNormalize(RawUplinkMessage raw) {
        String[] segments = raw.topic().split("/", -1);
        if (segments.length <= 5 || !"event".equals(segments[5])) return Optional.empty();
        if (segments.length != 7 || MqttUplinkTopic.parse(raw.topic()).isEmpty()
                || raw.qos() != 1 || raw.retained())
            throw new InvalidUplinkMessageException("EVENT_TOPIC_INVALID");
        EventUplinkMessageReader.Report report = reader.read(raw.payload());
        try {
            if (report.occurredAt().isAfter(raw.receivedAt().plus(MAX_FUTURE_SKEW)))
                throw new InvalidUplinkMessageException("EVENT_TIMESTAMP_INVALID");
            return Optional.of(new EventUplinkMessage(report.messageId(), raw.tenantId(), raw.projectId(),
                    raw.deviceId(), TransportProtocol.MQTT, segments[6], report.modelVersion(), report.occurredAt(),
                    raw.receivedAt(), raw.traceId(), raw.payload().length, report.params()));
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new InvalidUplinkMessageException("EVENT_ENVELOPE_INVALID");
        }
    }
}
