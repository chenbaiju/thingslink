package com.things.link.simulator.infrastructure.ota;

import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.ota.contract.OtaDeviceTransport;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 把已认证 {@link MqttDeviceClient} 适配成 OTA 传输端口的实现。
 *
 * <p><b>传输事实（不是承诺）：</b>本类把 OTA 上行固定为 QoS 1 且非 retained，与 ADR0124 及后续
 * OTA 协议冻结的投递语义一致；{@link #publish(String, byte[])} 返回的 future 只表示 MQTT 交付
 * 收据（Paho 的 PUBACK），<b>不</b>表示平台已解析、已采纳或已派发。适配器不做任何业务解释，
 * 也不读取 payload 内容。</p>
 *
 * <p><b>失败不吞：</b>{@link MqttDeviceClient#publish} 已在同步发起失败时返回异常完成的 future，
 * 传输层断开会异常完成在途交付；本类只把这些失败原样转交给调用方，绝不把「未确认」悄悄改写为
 * 成功，也不在失败时抛出同步异常（调用方的「已发起」计数仍需成立）。</p>
 */
public final class MqttOtaDeviceTransport implements OtaDeviceTransport {

    /** 设备 OTA 协议冻结的 QoS：至少一次。 */
    private static final int QOS_AT_LEAST_ONCE = 1;

    /** 设备 OTA 协议冻结的非保留投递。 */
    private static final boolean RETAINED = false;

    /** 已认证的单设备 MQTT 连接。 */
    private final MqttDeviceClient client;

    /**
     * @param client 已认证的单设备 MQTT 连接
     */
    public MqttOtaDeviceTransport(MqttDeviceClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 以 QoS 1、非 retained 发布一条设备上行。
     *
     * @param topic 已替换完成的完整 Topic
     * @param payload 已规范编码的字节
     * @return 与 MQTT 客户端同一交付收据；未获 PUBACK 时异常完成
     */
    @Override
    public CompletableFuture<Void> publish(String topic, byte[] payload) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        return client.publish(topic, payload, QOS_AT_LEAST_ONCE, RETAINED);
    }

    /**
     * 以 QoS 1 订阅下行 Topic，并把完整 Topic 原样交给处理器。
     *
     * @param topic 已替换完成的完整 Topic
     * @param handler 下行报文处理器
     */
    @Override
    public void subscribe(String topic, InboundHandler handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        client.subscribe(topic, QOS_AT_LEAST_ONCE, handler::onMessage);
    }

    /**
     * @return 底层 MQTT 连接当前是否在线
     */
    @Override
    public boolean isConnected() {
        return client.isConnected();
    }
}
