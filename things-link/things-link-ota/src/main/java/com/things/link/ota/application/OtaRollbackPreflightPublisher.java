package com.things.link.ota.application;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import java.time.Duration;
import java.time.Instant;

/** 单次真实只读回退预检查询传输，不执行重试，不把HTTP接受等同于设备接收。 */
public interface OtaRollbackPreflightPublisher extends AutoCloseable {
    /** 预留传输前检查受控配置；缺失不能占用真实发送预算。 */
    boolean configured();
    /** 单次受剩余期限约束的物理发布，正文必须为规范只读回退预检查询。 */
    Result publish(DeviceMqttDownlinkRoute route, byte[] canonical, Instant expiresAt, Instant operationDeadline, Duration budget);
    /** 取消本组件活跃Calls，不影响其他HTTP组件。 */
    void cancelActive();
    /** 拒绝新调用并关闭自身物理资源。 */
    @Override void close();
    /** 固定外部观察分类。 */
    enum Outcome {
        /** HTTP明确接受，不证明设备接收。 */ BROKER_ACCEPTED,
        /** HTTP明确拒绝。 */ REJECTED,
        /** 发送或回执结果不明。 */ UNKNOWN
    }
    /** 不保留供应商正文、请求头或原始异常。
     * @param outcome 观察分类
     * @param status 已观察HTTP状态，可空
     * @param reason 固定原因
     */
    record Result(Outcome outcome, Integer status, String reason) { }
}
