package com.things.link.ingestion.application;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.message.DevicePropertyReport;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.StandardUplinkMessage;

/**
 * 把 MQTT 原始载荷解析为冻结的设备报文，并转换成协议无关标准信封。
 *
 * <p>租户、项目与设备归属只采用 S3-11C 在鉴权后写入的原始信封，不允许设备 JSON 覆盖；
 * 否则伪造载荷可以把数据写入其他租户。S3-11D 只校验 {@link DevicePropertyReport} 的传输契约，
 * 属性类型与范围的物模型校验留在后续标准消息处理切片。</p>
 */
public class RawUplinkMessageNormalizer {

    /** 当前已实现的唯一消息类型；其他合法上行类型保留原始信封后进入 DLQ。 */
    private static final String PROPERTY_REPORT = "property/report";

    /** 与新协议接入共用的设备业务载荷读取器，保证跨协议契约与数字语义一致。 */
    private final DevicePropertyReportReader reportReader;

    /**
     * 创建原始消息标准化器。
     *
     * @param reportReader 设备业务载荷读取器
     */
    public RawUplinkMessageNormalizer(DevicePropertyReportReader reportReader) {
        this.reportReader = reportReader;
    }

    /**
     * 解析属性上报并构造标准信封。
     *
     * @param rawUplinkMessage 经 EMQX 接入层冻结的原始信封
     * @return 可供后续业务消费者使用的标准上行信封
     * @throws InvalidUplinkMessageException JSON 或设备报文契约不合法时抛出
     */
    public StandardUplinkMessage normalize(RawUplinkMessage rawUplinkMessage) {
        MqttUplinkTopic topic = MqttUplinkTopic.parse(rawUplinkMessage.topic())
                .orElseThrow(() -> new InvalidUplinkMessageException("原始上行 Topic 不符合 MQTT v1 契约"));
        if (!PROPERTY_REPORT.equals(topic.messageType())) {
            // 合法但尚未实现的类型必须显式进 DLQ，不能静默丢弃或误按属性 JSON 解析。
            throw new InvalidUplinkMessageException("暂不支持的消息类型: " + topic.messageType());
        }
        DevicePropertyReport report = reportReader.read(rawUplinkMessage.payload());

        return new StandardUplinkMessage(
                report.messageId(),
                rawUplinkMessage.tenantId(),
                rawUplinkMessage.projectId(),
                rawUplinkMessage.deviceId(),
                null,
                TransportProtocol.MQTT,
                StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT,
                report.modelVersion(),
                report.occurredAt(),
                rawUplinkMessage.receivedAt(),
                rawUplinkMessage.traceId(),
                rawUplinkMessage.payload().length,
                report.payload());
    }
}
