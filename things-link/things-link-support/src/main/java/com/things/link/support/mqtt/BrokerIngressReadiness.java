package com.things.link.support.mqtt;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记录固定 ingress 是否已经完成 Broker 连接与内部 Topic 订阅。
 *
 * <p>ADR 0046 的零 HTTP Action 切换不能在首个 durable session 建立前放行设备；否则设备认证与
 * ingress 首次订阅之间存在消息无人接管窗口。状态只表示当前订阅资格，不替代 Broker 会话持久性。</p>
 */
public final class BrokerIngressReadiness {
    /** 跨回调线程发布当前订阅状态。 */
    private final AtomicBoolean ready = new AtomicBoolean();

    /** @return 是否已完成固定 clientId 的连接与 QoS 1 订阅 */
    public boolean isReady() {
        return ready.get();
    }

    /** SUBACK 成功后放行设备认证。 */
    public void markReady() {
        ready.set(true);
    }

    /** 断线或关闭时停止放行新的设备连接。 */
    public void markUnavailable() {
        ready.set(false);
    }
}
