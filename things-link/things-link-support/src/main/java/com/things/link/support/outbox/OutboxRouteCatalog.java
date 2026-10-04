package com.things.link.support.outbox;

import java.util.Map;
import com.things.link.shared.message.AutomationPropertyAccepted;

/** Outbox 事件类型到固定 Kafka Topic 的唯一白名单，写入与发布必须共同复用。 */
public final class OutboxRouteCatalog {

    /** ADR0063：平台轮询结果的持久交付类型，业务写入和发布必须共用。 */
    public static final String MODBUS_NORMALIZED_EVENT_TYPE = "DEVICE_MODBUS_NORMALIZED";
    /** 复用已有标准上行主题，不由业务载荷推导目的地。 */
    public static final String MODBUS_NORMALIZED_TOPIC = "tc.device.uplink.normalized";
    /** 结果聚合固定为目标子设备，不能复用读请求的网关聚合身份。 */
    public static final String MODBUS_RESULT_AGGREGATE_TYPE = "DEVICE_MODBUS_RESULT";

    /** 冻结路由；不得从业务载荷或请求参数生成 Topic。 */
    private static final Map<String, String> ROUTES = Map.of(
            com.things.link.shared.message.PublicWebhookSource.EVENT_TYPE, com.things.link.shared.message.PublicWebhookSource.TOPIC,
            AutomationPropertyAccepted.EVENT_TYPE, AutomationPropertyAccepted.TOPIC,
            "DEVICE_COMMAND_DISPATCH", "tc.device.downlink",
            "DEVICE_COMMAND_TERMINAL", "tc.device.command.terminal",
            "DEVICE_TOPOLOGY_REPLY", "tc.device.topo.reply",
            "DEVICE_CONFIG_PUSH", "tc.device.config",
            "DEVICE_MODBUS_REQUEST", "tc.device.modbus.request",
            MODBUS_NORMALIZED_EVENT_TYPE, MODBUS_NORMALIZED_TOPIC,
            "ALARM_NOTIFICATION_DELIVERY_REQUEST", "tc.notification",
            "RULE_NOTIFICATION_DELIVERY_REQUEST", "tc.rule.notification");

    /** 工具目录禁止实例化。 */
    private OutboxRouteCatalog() {
    }

    /** @param eventType 冻结事件类型 @return 对应固定 Topic */
    public static String topicFor(String eventType) {
        String topic = ROUTES.get(eventType);
        if (topic == null) {
            throw new IllegalArgumentException("不支持的 Outbox 事件类型: " + eventType);
        }
        return topic;
    }
}
