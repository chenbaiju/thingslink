package com.things.link.device.application;

import com.things.link.shared.message.DeviceRealtimeUpdate;

import java.util.Objects;

/**
 * 设备 reported 属性已由事实事务接受的进程内事件。
 *
 * <p>事件在事务内发布，ingestion 的监听器以 {@code AFTER_COMMIT} 消费；因此事务回滚时
 * 不会产生一条指向不存在影子版本的实时增量。该事件自身不直接依赖 Kafka 或 Redis，避免
 * device 领域反向依赖接入适配模块。</p>
 *
 * @param update 可跨进程传递的实时增量快照
 */
public record DeviceReportedPropertiesCommitted(DeviceRealtimeUpdate update) {

    /**
     * 禁止空载荷事件进入事务监听器。
     */
    public DeviceReportedPropertiesCommitted {
        Objects.requireNonNull(update, "实时属性增量不能为空");
    }
}
