package com.things.link.device.infrastructure.emqx;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 设备连接事实的低基数指标门面。
 *
 * <p>架构文档 13.1 要求观测上下线速率。这里只使用代码冻结的事件类型；项目、设备、
 * clientId 与 Broker 节点都不能成为标签，否则设备规模会直接转化为 Prometheus 时序规模。</p>
 */
@Component
public class DeviceConnectionMetrics {

    /** 上下线事件计数器名称；Prometheus 会追加 {@code _total}。 */
    public static final String EVENTS = "thingslink.device.connection.events";

    /** 固定事件类型到计数器的映射。 */
    private final Map<EventType, Counter> events = new EnumMap<>(EventType.class);

    /**
     * 注册全部有限标签组合，避免第一次故障时才临时创建 meter。
     *
     * @param meterRegistry Micrometer 注册表
     */
    public DeviceConnectionMetrics(MeterRegistry meterRegistry) {
        for (EventType eventType : EventType.values()) {
            events.put(eventType, Counter.builder(EVENTS)
                    .description("已经持久化的设备连接与断开事件")
                    .tag("event", eventType.tagValue)
                    .register(meterRegistry));
        }
    }

    /** 记录一条已成功持久化的上线事件。 */
    public void recordConnected() {
        events.get(EventType.CONNECTED).increment();
    }

    /** 记录一条已成功关闭连接事实的下线事件。 */
    public void recordDisconnected() {
        events.get(EventType.DISCONNECTED).increment();
    }

    /** Prometheus 允许使用的固定连接事件类型。 */
    private enum EventType {
        /** MQTT 会话建立。 */
        CONNECTED("connected"),
        /** MQTT 会话关闭。 */
        DISCONNECTED("disconnected");

        /** 低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue Prometheus 标签值
         */
        EventType(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
