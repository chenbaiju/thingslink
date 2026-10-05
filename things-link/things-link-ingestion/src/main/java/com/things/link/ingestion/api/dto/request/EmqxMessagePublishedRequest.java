package com.things.link.ingestion.api.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * EMQX {@code message.publish} 规则回调契约。
 *
 * <p>payload 在 Broker 规则中先做 Base64，避免任意二进制载荷破坏外层 JSON；入口解码后原样写入
 * Kafka，不在本层解析设备业务报文。</p>
 *
 * @param username 已认证 MQTT 用户名，格式为 projectKey/deviceKey
 * @param topic Broker 实际接收的 Topic
 * @param payloadBase64 原始 MQTT payload 的 Base64 文本
 * @param qos MQTT 服务质量等级
 * @param retained MQTT retained 标志
 * @param clientId Broker 观察到的客户端 ID
 * @param publishedAtMs Broker 接收消息的 Unix 毫秒时间
 */
@Schema(name = "EmqxMessagePublishedRequest", description = "EMQX 消息发布规则回调")
public record EmqxMessagePublishedRequest(
        @NotBlank String username,
        @NotBlank String topic,
        @NotBlank @JsonProperty("payload_base64") String payloadBase64,
        @NotNull Integer qos,
        @NotNull Boolean retained,
        @NotBlank @JsonProperty("clientid") String clientId,
        @NotNull @JsonProperty("published_at_ms") Long publishedAtMs) {
}
