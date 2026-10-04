package com.things.link.simulator.application;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** 已认证的单设备 MQTT 连接端口。 */
public interface MqttDeviceClient {

    /**
     * 订阅设备专属的下行 Topic。
     *
     * <p>设备端只允许订阅 {@code down/#}；模拟器在同一条已认证连接上订阅，才能验证
     * EMQX ACL 没有因另建匿名连接而被绕过。适配器必须保留 Broker 投递的完整 Topic，
     * 否则上层无法验证回复只能对应本设备收到的命令。</p>
     *
     * @param topic 完整 MQTT 订阅 Topic，可包含末尾通配符
     * @param qos 请求的 MQTT QoS
     * @param handler 下行消息处理器
     */
    void subscribe(String topic, int qos, MqttDeviceMessageHandler handler);

    /**
     * 发布设备消息，返回 QoS 1 的 PUBACK 收据。
     *
     * <p>返回的 future 在 Broker 回 PUBACK（异步客户端 {@code IMqttActionListener.onSuccess}）时正常完成；
     * 连接在 PUBACK 前断开、或同步发起失败（客户端已关闭等）则异常完成。调用方据此区分
     * 「已交给 Broker 但未确认」与「Broker 已确认」——这是 A4-0 负载发生器资格要求的成功集合口径，
     * 不能用「发起发布」冒充「发布成功」。实现不得在同步失败时抛出：必须返回一个已异常完成的 future，
     * 使调用方「发起」计数仍成立、失败由同一收据记账，避免「失败 > 发起」的计数裂口。</p>
     *
     * @param topic 完整 Topic
     * @param payload UTF-8 或二进制载荷
     * @param qos MQTT QoS
     * @param retained 是否保留消息
     * @return 发布交付收据；连接断开前未收到 PUBACK 时以异常完成
     */
    CompletableFuture<Void> publish(String topic, byte[] payload, int qos, boolean retained);

    /**
     * 不发送 MQTT DISCONNECT 包而强制切断网络连接，并进入生产重连退避。
     *
     * <p>该入口只供模拟器故障演练；正常停止必须调用 {@link #close()}，否则 Broker 会把主动停机
     * 错误观测为异常掉线。</p>
     */
    void forceConnectionLoss();

    /** @return 当前是否已与 Broker 建立 MQTT 会话 */
    boolean isConnected();

    /**
     * @return 成功连接代次；首次连接为 1，每次故障恢复后单调递增
     */
    long connectionGeneration();

    /** @return 最近一次成功连接完成时刻，用于量化重连是否被抖动分散 */
    Instant lastConnectedAt();

    /** 主动断开并释放网络资源。 */
    void close();
}
