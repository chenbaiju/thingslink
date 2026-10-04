package com.things.link.ingestion.application;

import com.things.link.shared.message.StandardUplinkMessage;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * 设备发生时间相对平台接收时间的可信边界。
 *
 * <p>D-039 只拒绝超过容忍窗口的未来时间，不拒绝迟到或时钟回拨消息。后者仍需进入历史事实，
 * 并由影子属性级 CAS 防止旧值覆盖新值。比较使用信封冻结的 {@code receivedAt}，保证 Kafka 重放
 * 不会因消费节点墙钟变化而得到不同结论。</p>
 */
public final class UplinkTimestampPolicy {

    /** 允许设备时钟领先平台接收时间的最大窗口。 */
    private final Duration maxFutureSkew;

    /**
     * @param maxFutureSkew 最大未来偏差，必须为正值
     */
    public UplinkTimestampPolicy(Duration maxFutureSkew) {
        if (maxFutureSkew == null || maxFutureSkew.isZero() || maxFutureSkew.isNegative()) {
            throw new IllegalArgumentException("maxFutureSkew 必须为正值");
        }
        this.maxFutureSkew = maxFutureSkew;
    }

    /**
     * 拒绝超出未来窗口的标准上行；等于边界时仍接受。
     *
     * @param message 已由接入层确权并冻结接收时间的标准信封
     * @throws InvalidUplinkMessageException 设备发生时间超过最大未来偏差
     */
    public void validate(StandardUplinkMessage message) {
        final Instant latestAccepted;
        try {
            latestAccepted = message.receivedAt().plus(maxFutureSkew);
        } catch (DateTimeException | ArithmeticException exception) {
            // receivedAt 来自平台；若其与配置组合已溢出，继续放行会失去防毒化边界，必须 fail-closed。
            throw new InvalidUplinkMessageException("平台接收时间无法计算未来偏差边界", exception);
        }
        if (message.occurredAt().isAfter(latestAccepted)) {
            throw new InvalidUplinkMessageException("设备发生时间超过最大未来偏差");
        }
    }
}
