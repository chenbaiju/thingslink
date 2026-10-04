package com.things.link.ingestion.application;

import com.things.link.ota.application.OtaDeviceReportIngestionService;
import com.things.link.shared.message.RawUplinkMessage;
import org.springframework.stereotype.Component;

/** 与其他上行协议处理器一样在application内识别Topic，只将OTA报告交给本域事务端口。 */
@Component
public class OtaReportUplinkHandler {
    /** OTA公开接纳事务，不在ingestion跨域读写报告表。 */
    private final OtaDeviceReportIngestionService reports;

    /** 强制装配实际报告处理器，不提供可选的无效空实现。 */
    public OtaReportUplinkHandler(OtaDeviceReportIngestionService reports) { this.reports = reports; }

    /** 非OTA报文继续原流程；协议永久拒绝在原事务结束后映射既有DLQ分类。 */
    public boolean tryAccept(RawUplinkMessage raw) {
        var topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null || !"ota/report".equals(topic.messageType())) return false;
        try {
            reports.accept(raw.authenticatedIdentity(), raw.payload(), raw.receivedAt());
        } catch (IllegalArgumentException failure) {
            throw new InvalidUplinkMessageException("OTA报告未满足认证运行合同");
        }
        return true;
    }
}
