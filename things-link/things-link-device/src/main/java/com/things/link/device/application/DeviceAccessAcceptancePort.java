package com.things.link.device.application;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 协议无关的接入幂等受理端口。
 *
 * <p>接入面（HTTP／TCP／CoAP）在把标准上行交给消息总线前后各调用一次本端口：先 {@link #decide} 判定
 * 这次尝试是不是重放或同键异载荷，总线确认接管后再 {@link #record} 落受理事实。把判定与落库拆成两步是
 * 刻意的——受理的唯一判据是总线接管，事实只是**受理证据**，不是先占的锁；先写事实再发布会在发布失败时
 * 留下「已受理但消息从未上车」的假事实。</p>
 *
 * <p>MQTT 不经过本端口：它没有受理响应，端到端幂等由 QoS 1 与业务侧 {@code sys_inbox_message} 承担。</p>
 */
public interface DeviceAccessAcceptancePort {

    /**
     * 一次接入尝试的幂等键与载荷摘要。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 认证时权威项目，同时是 RLS 范围
     * @param deviceId 认证时权威设备
     * @param messageId 设备生成的 UUIDv7 消息标识
     * @param protocol 本次接入使用的传输协议
     * @param payloadDigest 业务载荷 SHA-256 十六进制摘要
     * @param receivedAt 本次接入面收到完整报文的平台时刻
     */
    record Attempt(UUID tenantId, UUID projectId, UUID deviceId, UUID messageId, TransportProtocol protocol,
                   String payloadDigest, Instant receivedAt) {
        /** 冻结幂等尝试的必填字段。 */
        public Attempt {
            if (tenantId == null || projectId == null || deviceId == null || messageId == null) {
                throw new IllegalArgumentException("接入尝试的归属与消息标识不能为空");
            }
            if (protocol == null || protocol == TransportProtocol.MQTT) {
                throw new IllegalArgumentException("接入幂等只面向 HTTP／TCP／CoAP");
            }
            if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("载荷摘要必须是 SHA-256 十六进制");
            }
            if (receivedAt == null) {
                throw new IllegalArgumentException("接入接收时刻不能为空");
            }
        }
    }

    /**
     * 已受理事实。
     *
     * @param deviceId 设备 ID
     * @param messageId 设备消息标识
     * @param payloadDigest 首次受理时的载荷摘要
     * @param protocol 首次受理使用的传输协议
     * @param receivedAt 首次受理时接入面收到报文的平台时刻
     * @param acceptedAt 受理事实写入时刻（数据库时钟）
     */
    record Acceptance(UUID deviceId, UUID messageId, String payloadDigest, TransportProtocol protocol,
                      Instant receivedAt, Instant acceptedAt) {
    }

    /** 幂等判定结果。 */
    sealed interface Decision {

        /** 该 (设备, messageId) 从未被受理，可以继续交接。 */
        record Fresh() implements Decision {
        }

        /**
         * 同键同摘要：设备重放，返回首次结果且不得再次交接。
         *
         * @param acceptance 首次受理事实
         */
        record Duplicate(Acceptance acceptance) implements Decision {
        }

        /**
         * 同键异摘要：同一 messageId 换了载荷，必须拒绝并保持首次事实不变。
         *
         * @param acceptance 首次受理事实
         */
        record Conflict(Acceptance acceptance) implements Decision {
        }
    }

    /**
     * 受理事实写入结果。
     *
     * @param firstRecording 本次是否首次写入；false 表示同键事实已由并发重试先写入
     * @param acceptance 最终生效的受理事实
     */
    record Recording(boolean firstRecording, Acceptance acceptance) {
    }

    /**
     * 受理前幂等判定。
     *
     * @param attempt 本次尝试
     * @return 未受理过／重放／同键异载荷
     */
    Decision decide(Attempt attempt);

    /**
     * 记录受理事实；同键已存在时不覆盖并返回既有事实。
     *
     * @param attempt 本次尝试
     * @return 写入结果与最终生效的事实
     */
    Recording record(Attempt attempt);
}
