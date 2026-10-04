package com.things.link.simulator.application.ota.contract;

import java.util.concurrent.CompletableFuture;

/**
 * 设备侧 OTA 传输端口：只搬运字节，不解释业务，也不声称任何平台事实。
 *
 * <p><b>为什么单独抽象：</b>MQTT 适配器与无 Broker 的测试必须共用同一个缝。测试用假实现证明
 * 「入站平台报文被路由到状态机、出站报文落到正确 Topic」，生产用 Paho 适配器保证 QoS 1、
 * 非 retained 和失败可观测；两者之间只有这个方法集。</p>
 *
 * <p><b>固定传输语义：</b>OTA 设备协议要求 QoS 1 且非 retained（ADR0124 同时固定 QOS1 且
 * 非 retained，后续 OTA 协议沿此）。端口不暴露 qos/retained 参数，避免调用方用 QoS 0
 * 或 retained 悄悄改变平台已冻结的投递语义——那两类改动都会让平台看到与设备不同的世界。</p>
 *
 * <p><b>本端口不做的事：</b>不解析 JSON、不校验签名、不判断某次「发布成功」等价于平台已经
 * 采纳。{@link #publish(String, byte[])} 的 future 只表示传输层交付收据（Paho 的 PUBACK）。</p>
 */
public interface OtaDeviceTransport {

    /**
     * 发布一条设备上行报文。
     *
     * <p>实现必须使用 QoS 1 与非 retained。交付失败必须通过返回的 future 异常完成来上报，
     * 不能吞掉：调用方靠它区分「已交给客户端」与「Broker 已确认」。</p>
     *
     * @param topic 已替换完成的完整 Topic
     * @param payload 已规范编码的 UTF-8 字节
     * @return 传输交付收据；未获 Broker 确认时异常完成
     */
    CompletableFuture<Void> publish(String topic, byte[] payload);

    /**
     * 订阅一个下行 Topic。
     *
     * <p>订阅必须保留 Broker 投递的完整 Topic，使上层能复核「回复只对应本设备收到的命令」。</p>
     *
     * @param topic 已替换完成的完整 Topic
     * @param handler 下行报文处理器
     */
    void subscribe(String topic, InboundHandler handler);

    /**
     * @return 当前是否已与 Broker 建立会话
     */
    boolean isConnected();

    /**
     * 下行报文处理器；payload 保持原始字节，由协议层决定是否按 UTF-8 JSON 解析。
     */
    @FunctionalInterface
    interface InboundHandler {

        /**
         * 处理一条匹配订阅的下行报文。
         *
         * @param topic Broker 投递的完整 Topic
         * @param payload 原始 MQTT payload
         */
        void onMessage(String topic, byte[] payload);
    }
}
