package com.things.link.ingestion.application;

import java.time.Instant;

/**
 * 无法解析的 Broker 信封进入统一 DLQ 时使用的脱敏事实。
 *
 * @param schemaVersion 死信记录版本
 * @param handoffId 能安全读取时保留的传输标识，否则为 null
 * @param reason 固定低基数失败分类
 * @param envelopeSha256 原始信封字节摘要，禁止归档明文 payload
 * @param observedAt ingress 观察失败的时间
 */
public record BrokerHandoffDeadLetter(int schemaVersion, String handoffId, String reason,
                                      String envelopeSha256, Instant observedAt) {
}
