package com.things.link.device.application;

/**
 * 等价配置交付在项目冻结后被持续写许可拒绝。
 *
 * <p>配置域没有独立delivery终态；ingestion将其作为PROJECT_FROZEN永久信封错误交给现有DLQ保存。</p>
 */
public class ProjectFrozenConfigDeliveryException extends RuntimeException {

    /** 创建不携带业务载荷的稳定冻结拒绝。 */
    public ProjectFrozenConfigDeliveryException() {
        super("PROJECT_FROZEN: 项目已冻结，设备配置停止交付");
    }
}
