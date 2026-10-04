package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmNotificationDelivery;

/** PUSH 厂商通道端口；调用方保证授权复核完成且外部调用不持有数据库事务。 */
public interface PushNotificationSender {

    /**
     * 执行一次安装实例级发送。
     *
     * @param delivery 已经 CAS 为 SENDING 的 PUSH 投递事实
     * @param target 刚完成复核和解密的短生命周期目标
     * @return 厂商消息 ID；确定性桩返回不含 token 的稳定 ID
     */
    String send(
            AlarmNotificationDelivery delivery,
            AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget target);
}
