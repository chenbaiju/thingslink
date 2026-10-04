package com.things.link.ingestion.application;

/**
 * durable ingress 在下游调用返回后可安全执行的固定 ACK 分类。
 *
 * <p>暂时故障仍以异常向上冒泡；{@link #TRANSIENT_RETRY} 只供资格证据标记异常路径，
 * 绝不能作为正常返回值或触发 MQTT ACK。</p>
 */
public enum HandoffDisposition {
    /** 下游持久事实已经接管，可以确认 MQTT 投递。 */
    ACCEPTED,
    /** 幂等键已经生效，无需再次改变事实但可以确认本次传输重放。 */
    DUPLICATE,
    /** 协议、身份或配额永久拒绝，继续重放不会恢复，可以确认并计数。 */
    PERMANENT_REJECT,
    /** Broker 信封损坏且死信已获 Kafka ACK，可以确认并解除 poison 阻塞。 */
    QUARANTINED,
    /** 资格证据专用的暂时故障标记；分派器记录后必须继续抛出原异常。 */
    TRANSIENT_RETRY
}
