package com.things.link.ingestion.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;

/**
 * ADR 0046 冻结的 Broker 交接信封 v1。
 *
 * @param handoffId EMQX 为原始 PUBLISH 生成的传输标识
 * @param username Broker 已认证的设备用户名
 * @param topic 原始设备上行 Topic
 * @param payloadBase64 原始 payload 的标准 Base64
 * @param qos 原始 QoS，v1 只允许 1
 * @param retained 原始 retained 标志，v1 只允许 false
 * @param clientId 原发布客户端 ID，仅用于诊断
 * @param publishedAtMs Broker 接收原消息的毫秒时间
 * @param authenticatedIdentity 认证时代际，旧信封为空且不得补为当前版本
 * @param brokerNode 触发规则的 EMQX 节点
 */
public record BrokerHandoffEnvelope(String handoffId, String username, String topic, String payloadBase64,
                                    int qos, boolean retained, String clientId, long publishedAtMs,
                                    String brokerNode, AuthenticatedDeviceIdentity authenticatedIdentity) {
    /** 旧交接入口保持空认证证据。 */
    public BrokerHandoffEnvelope(String handoffId, String username, String topic, String payloadBase64,
                                 int qos, boolean retained,
                                 String clientId, long publishedAtMs, String brokerNode) {
        this(handoffId, username, topic, payloadBase64, qos, retained, clientId, publishedAtMs, brokerNode, null);
    }
}
