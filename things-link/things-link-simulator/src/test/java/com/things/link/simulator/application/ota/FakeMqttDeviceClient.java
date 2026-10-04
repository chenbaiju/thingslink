package com.things.link.simulator.application.ota;

import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.MqttDeviceMessageHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 测试用假 MQTT 客户端：只记录「已发起发布」「已安装订阅」「订阅 QoS」三项可观察事实，并允许测试手动投递下行报文。
 *
 * <p><b>为什么必须共享而不是每个测试各写一份：</b>OTA 接线的验证前提是「同一套假 Broker 语义」——
 * 发布顺序、QoS 1、非 retained、以及按精确 Topic 分发入站。若各测试各自实现，很容易出现某个用例
 * 悄悄放宽了入站匹配（例如用前缀匹配替代精确匹配），把真实的 Topic 拼接错误掩盖成通过。抽成一个
 * 共享替身以后，传输适配器测试与运行时测试看到的是完全一致的 Broker 行为。</p>
 *
 * <p><b>本替身刻意不模拟的：</b>不模拟 PUBACK 延迟、不模拟重连、不做任何业务解释。交付成败只由
 * {@link #failNextPublish(Throwable)} 与返回的 future 表达，避免「假 Broker 顺带证明了平台已采纳」。</p>
 */
public final class FakeMqttDeviceClient implements MqttDeviceClient {

    /** 已发起的发布，按调用顺序。 */
    private final List<Publication> publications = new ArrayList<>();

    /** 已安装的订阅处理器，按订阅顺序。 */
    private final Map<String, MqttDeviceMessageHandler> handlers = new LinkedHashMap<>();

    /** 每个订阅请求的 QoS。 */
    private final Map<String, Integer> subscriptionQos = new LinkedHashMap<>();

    /** 下一次发布的交付收据。 */
    private CompletableFuture<Void> nextReceipt = CompletableFuture.completedFuture(null);

    /** 连接状态。 */
    private boolean connected = true;

    /**
     * @param topic 订阅 Topic
     * @param qos 订阅 QoS
     * @param handler 下行处理器
     */
    @Override
    public void subscribe(String topic, int qos, MqttDeviceMessageHandler handler) {
        handlers.put(topic, handler);
        subscriptionQos.put(topic, qos);
    }

    /**
     * @param topic 完整 Topic
     * @param payload 载荷
     * @param qos QoS
     * @param retained 是否 retained
     * @return 本次交付收据
     */
    @Override
    public CompletableFuture<Void> publish(String topic, byte[] payload, int qos, boolean retained) {
        publications.add(new Publication(topic, payload, qos, retained));
        return nextReceipt;
    }

    /** 置为断线状态。 */
    @Override
    public void forceConnectionLoss() {
        connected = false;
    }

    /**
     * @return 当前是否在线
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * @return 固定连接代次
     */
    @Override
    public long connectionGeneration() {
        return 1L;
    }

    /**
     * @return 固定连接完成时刻
     */
    @Override
    public Instant lastConnectedAt() {
        return Instant.EPOCH;
    }

    /** 关闭连接。 */
    @Override
    public void close() {
        connected = false;
    }

    /**
     * 让下一次发布以失败收据返回。
     *
     * @param failure 失败原因
     */
    public void failNextPublish(Throwable failure) {
        this.nextReceipt = CompletableFuture.failedFuture(failure);
    }

    /**
     * 模拟 Broker 按精确 Topic 投递一条下行报文。
     *
     * @param topic 投递 Topic，必须已有精确匹配的订阅
     * @param payload 原始载荷
     * @throws IllegalStateException 没有精确匹配的订阅时
     */
    public void deliver(String topic, byte[] payload) {
        MqttDeviceMessageHandler handler = handlers.get(topic);
        if (handler == null) {
            throw new IllegalStateException("没有匹配的订阅: " + topic);
        }
        handler.onMessage(topic, payload);
    }

    /**
     * @return 已发起发布的不可变副本
     */
    public List<Publication> publications() {
        return List.copyOf(publications);
    }

    /**
     * @return 已发布 Topic 列表（按首次出现顺序去重）
     */
    public Set<String> topics() {
        return publications.stream().map(Publication::topic)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * @return 已安装的订阅处理器对应 Topic 集合
     */
    public Set<String> handlers() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * @return 每个订阅 Topic 的 QoS
     */
    public Map<String, Integer> subscriptionQos() {
        return Map.copyOf(subscriptionQos);
    }

    /**
     * 一次发布尝试的原始事实。
     *
     * @param topic 完整 Topic
     * @param payload 载荷字节
     * @param qos QoS
     * @param retained 是否 retained
     */
    public record Publication(String topic, byte[] payload, int qos, boolean retained) {

        /** 冻结调用方缓冲区。 */
        public Publication {
            payload = payload.clone();
        }

        /**
         * @return 独立载荷副本
         */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
