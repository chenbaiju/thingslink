package com.things.link.ingestion.application;

import com.things.link.ota.application.OtaCommitReconciliationIngestionService;
import com.things.link.shared.message.RawUplinkMessage;
import org.springframework.stereotype.Component;

/** 仅分流认证MQTT提交对账，不创建设备HTTP控制面。 */
@Component
public class OtaCommitReconciliationUplinkHandler {
    /** 负责真实身份与持久申请的OTA事务服务。 */
    private final OtaCommitReconciliationIngestionService requests;

    /** 接线强制依赖，不提供忽略申请的默认实现。 */
    public OtaCommitReconciliationUplinkHandler(OtaCommitReconciliationIngestionService requests) {
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
    }

    /** 保留原认证代际和平台接收时间；只有永久合同拒绝进入协议错误分类。 */
    public boolean tryAccept(RawUplinkMessage raw) {
        var topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null || !"ota/commit/reconciliation/report".equals(topic.messageType())) return false;
        try {
            requests.accept(raw.authenticatedIdentity(), raw.payload(), raw.receivedAt());
        } catch (IllegalArgumentException failure) {
            throw new InvalidUplinkMessageException("OTA提交对账未满足认证运行合同");
        }
        return true;
    }
}
