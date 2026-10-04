package com.things.link.device.application;

/**
 * 配置Kafka信封缺少等价原Outbox或违反冻结身份合同。
 *
 * <p>该错误重放不会自愈，ingestion只把此专用类型转换为永久下行错误；数据库异常不得包装为本类型。</p>
 */
public class InvalidDeviceConfigPushException extends RuntimeException {

    /** @param message 不含配置点位内容的稳定诊断 */
    public InvalidDeviceConfigPushException(String message) {
        super(message);
    }
}
