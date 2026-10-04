package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.shared.id.Uuid7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** ADR0143：每个Spring运行实例唯一生成，标签不得充当会话所有者或Kafka组身份。 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component("deviceAccessTcpRuntime")
public class DeviceAccessTcpRuntime {
    /** 启动时冻结的UUIDv7，在本进程所有TCP组件间共享。 */
    private final String id = Uuid7.generate().toString();
    /** 部署身份前缀由独立应用覆盖，原平台默认值保持兼容。 */
    @Value("${things-link.kafka.group-prefix:things-link}")
    private String groupPrefix = "things-link";
    /** @return 会话权威owner */
    public String id() { return id; }
    /** @return 本进程独立广播消费组 */
    public String groupId() { return groupPrefix + "-ingestion-tcp-downlink." + id; }
}
