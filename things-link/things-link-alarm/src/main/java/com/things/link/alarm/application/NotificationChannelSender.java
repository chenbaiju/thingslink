package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;

/** 一个冻结投递快照到外部渠道的同步适配端口；调用方保证不持有数据库事务。 */
public interface NotificationChannelSender {
    /** @return 本适配器唯一支持的渠道 */
    NotificationChannel channel();

    /**
     * 阻塞发送一次；成功返回供应商消息 ID，失败抛出脱敏且带可重试性的异常。
     *
     * @param delivery 已经 CAS 为 SENDING 的冻结投递
     * @return 供应商消息 ID；渠道无返回值时使用稳定 deliveryId
     */
    String send(AlarmNotificationDelivery delivery);
}
