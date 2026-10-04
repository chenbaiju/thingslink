package com.things.link.ingestion.application;

import com.things.link.ota.application.OtaRollbackIngestionService;
import com.things.link.shared.message.RawUplinkMessage;
import org.springframework.stereotype.Component;

/** 仅分流认证MQTT回退执行观察，不创建设备HTTP控制面。 */
@Component
public class OtaRollbackUplinkHandler {
    /** 负责真实身份与持久申请的OTA事务服务。 */
    private final OtaRollbackIngestionService requests;

    /** 接线强制依赖，不提供忽略申请的默认实现。 */
    public OtaRollbackUplinkHandler(OtaRollbackIngestionService requests) {
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
    }

    /** 保留原认证代际和平台接收时间；只有永久合同拒绝进入协议错误分类。 */
    public boolean tryAccept(RawUplinkMessage raw) {
        var topic = MqttUplinkTopic.parse(raw.topic()).orElse(null);
        if (topic == null) return false;
        boolean operation = "ota/rollback/operation/report".equals(topic.messageType());
        boolean status = "ota/rollback/status/report".equals(topic.messageType());
        if (!operation && !status) return false;
        try {
            if (operation) requests.acceptOperation(raw.authenticatedIdentity(), raw.payload(), raw.receivedAt());
            else requests.acceptStatus(raw.authenticatedIdentity(), raw.payload(), raw.receivedAt());
        } catch (IllegalArgumentException failure) {
            throw new InvalidUplinkMessageException("OTA回退执行观察未满足认证运行合同");
        }
        return true;
    }
}
