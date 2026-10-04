package com.things.link.support.notification.delivery;

/** 告警与规则共用的真实外部通知渠道端口；调用方必须保证不在数据库事务内执行。 */
public interface ExternalNotificationSender {

    /** 邮件渠道 bean 名，业务域只按固定限定符装配。 */
    String EMAIL_BEAN = "externalEmailNotificationSender";

    /** Webhook 渠道 bean 名，业务域只按固定限定符装配。 */
    String WEBHOOK_BEAN = "externalWebhookNotificationSender";

    /** @return 固定 EMAIL 或 WEBHOOK 渠道 */
    String channel();

    /**
     * 阻塞执行一次真实发送。
     *
     * @param request 冻结且低敏的外部通知请求
     * @return 供应商消息 ID；渠道无 ID 时使用稳定 deliveryId
     */
    String send(ExternalNotificationRequest request);
}
