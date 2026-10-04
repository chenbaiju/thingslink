package com.things.link.ingestion.application.access;

import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.shared.message.DevicePropertyReport;
import com.things.link.shared.message.StandardUplinkMessage;

/**
 * 把新协议接入信封转换为协议无关标准上行。
 *
 * <p>三种新协议共用同一份业务载荷契约与同一套确权规则，因此标准化只能有一处实现：归属字段全部取自
 * {@link DeviceAccessUplinkMessage}，业务载荷只贡献消息标识、发生时间、物模型版本与属性值。协议保留在
 * 标准信封上供消息日志与调试面展示，但物模型校验、遥测落库、影子与规则都不感知传输协议。</p>
 *
 * <p>设备发生时间在这里先做一次 fail-closed 校验：接入面必须能在受理前把永久非法的未来时间映射为
 * 协议错误（HTTP 400／CoAP 4.00），而不是先返回受理再让消息在标准管道里进 DLQ。下游
 * {@code NormalizedUplinkKafkaConsumer} 的同一策略仍然保留，用于兜住 MQTT 与任何其他生产者。</p>
 */
public class DeviceAccessUplinkNormalizer {

    /** 与 MQTT 共用的业务载荷读取器，保证数字精度与契约校验完全一致。 */
    private final DevicePropertyReportReader reportReader;

    /** 设备未来时间边界，使用与 MQTT 相同的配置值。 */
    private final UplinkTimestampPolicy timestampPolicy;

    /**
     * 创建新协议接入标准化器。
     *
     * @param reportReader 业务载荷读取器
     * @param timestampPolicy 设备未来时间边界
     */
    public DeviceAccessUplinkNormalizer(DevicePropertyReportReader reportReader,
                                        UplinkTimestampPolicy timestampPolicy) {
        this.reportReader = reportReader;
        this.timestampPolicy = timestampPolicy;
    }

    /**
     * 解析业务载荷并构造标准上行信封。
     *
     * @param uplink 接入面冻结的新协议信封
     * @return 可供 telemetry／影子／规则消费的标准上行信封
     * @throws com.things.link.ingestion.application.InvalidUplinkMessageException 载荷违反冻结契约或时间超限
     */
    public StandardUplinkMessage normalize(DeviceAccessUplinkMessage uplink) {
        DevicePropertyReport report = reportReader.read(uplink.businessPayload());
        if (report == null) {
            throw new InvalidUplinkMessageException("属性上报必须为 JSON 对象，不能为 null");
        }
        StandardUplinkMessage message = new StandardUplinkMessage(
                report.messageId(),
                uplink.tenantId(),
                uplink.projectId(),
                uplink.deviceId(),
                null,
                uplink.protocol(),
                StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT,
                report.modelVersion(),
                report.occurredAt(),
                uplink.receivedAt(),
                uplink.traceId(),
                uplink.businessPayload().length,
                report.payload());
        timestampPolicy.validate(message);
        return message;
    }
}
