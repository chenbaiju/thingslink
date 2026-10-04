package com.things.link.ingestion.infrastructure.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S4-4 WebSocket 的硬资源上限。
 *
 * <p>这些不是可随意调大的性能参数：每个数字都限制不可信浏览器占用本机连接、内存队列和
 * 项目扇出成本。超过上限时连接或订阅失败，慢客户端关闭为 1013，而不是积压到影响其他租户。</p>
 *
 * @param maxGlobalConnections 本实例全部活跃会话上限
 * @param maxAccountConnections 单账号活跃会话上限
 * @param maxProjectConnections 单项目活跃会话上限
 * @param maxSessionSubscriptions 单会话去重后的 device × property 上限
 * @param maxAccountSubscriptions 单账号全部会话订阅上限
 * @param maxProjectSubscriptions 单项目全部会话订阅上限
 * @param outboundQueueCapacity 单会话待发送属性更新上限
 * @param maxTextFrameBytes 接收文本帧最大字节数
 * @param sendTimeoutMillis 底层容器异步发送超时
 */
@ConfigurationProperties(prefix = "things-link.ingestion.realtime")
public record RealtimeWebSocketProperties(int maxGlobalConnections, int maxAccountConnections,
                                          int maxProjectConnections, int maxSessionSubscriptions,
                                          int maxAccountSubscriptions, int maxProjectSubscriptions,
                                          int outboundQueueCapacity, int maxTextFrameBytes,
                                          long sendTimeoutMillis) {

    /**
     * 所有上限必须为正数；漏配或负数时启动失败比无界运行安全。
     */
    public RealtimeWebSocketProperties {
        if (maxGlobalConnections <= 0 || maxAccountConnections <= 0 || maxProjectConnections <= 0
                || maxSessionSubscriptions <= 0 || maxAccountSubscriptions <= 0 || maxProjectSubscriptions <= 0
                || outboundQueueCapacity <= 0 || maxTextFrameBytes <= 0 || sendTimeoutMillis <= 0) {
            throw new IllegalArgumentException("WebSocket 实时推送上限必须全部大于零");
        }
    }
}
