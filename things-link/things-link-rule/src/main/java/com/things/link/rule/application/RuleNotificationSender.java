package com.things.link.rule.application;

/** 在规则 Kafka 消费边界把冻结快照同步发送到一个固定外部渠道。 */
public interface RuleNotificationSender {

    /** @return 本适配器唯一支持的固定渠道名 */
    String channel();

    /**
     * 执行一次真实发送；实现不得记录完整收件目标、正文或签名密钥。
     *
     * @param delivery 已经 CAS 为 SENDING 的冻结快照
     * @return 低敏供应商消息 ID；无供应商 ID 时返回以 deliveryId 派生的稳定值
     */
    String send(RuleNotificationDelivery delivery);
}
