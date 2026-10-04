package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.shared.message.AlarmStateInvalidated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/** ADR0109：只发布当前事务内的无正文失效事件，外部发送必须由AFTER_COMMIT消费方执行。 */
final class AlarmStateInvalidationPublisher {
    /** 固定诊断不暴露告警正文、设备或凭据。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmStateInvalidationPublisher.class);

    /** 工具类不创建实例。 */
    private AlarmStateInvalidationPublisher() {
    }

    /**
     * 提示是可丢失旁路，应用事件总线异常不能改变告警事实提交语义。
     * @param publisher Spring生命周期注入的事件总线，独立new构造单测可无总线
     * @param instance 已成功追加状态迁移的权威实例
     */
    static void publish(ApplicationEventPublisher publisher, AlarmInstance instance) {
        if (publisher == null) return;
        try {
            publisher.publishEvent(new AlarmStateInvalidated(instance.tenantId(), instance.projectId(), instance.originatorId()));
        } catch (RuntimeException failure) {
            LOGGER.warn("告警前台失效事件发布失败，等待REST权威校准");
        }
    }
}
