package com.things.link.dashboard.application.publication;

import java.util.Objects;

/** 看板发布候选缺少外部资格时的安全失败关闭异常。 */
public class DashboardPublicationQualificationException extends RuntimeException {

    /** 不携带外部事实身份或权威值的稳定拒绝原因。 */
    private final Reason reason;

    /** @param reason 稳定拒绝原因 */
    public DashboardPublicationQualificationException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message);
        this.reason = reason;
    }

    /** @return 稳定且不泄露外部事实的拒绝原因 */
    public Reason reason() {
        return reason;
    }

    /** 发布资格编排的稳定失败分类。 */
    public enum Reason {
        /** 实际宿主未登记精确组件或不在声明兼容范围。 */
        HOST_COMPONENT_UNAVAILABLE("看板宿主组件不可用"),
        /** 实际宿主未登记精确内置资源摘要。 */
        BUILTIN_RESOURCE_UNAVAILABLE("看板宿主资源不可用"),
        /** 物模型版本不存在、错项目或摘要/Profile不匹配。 */
        MODEL_REFERENCE_INVALID("看板模型引用不可用"),
        /** 默认设备不存在、错项目或当前模型绑定不匹配。 */
        DEFAULT_DEVICE_INVALID("看板默认设备不可用"),
        /** 顶层模型属性不存在或dataType不匹配。 */
        MODEL_PROPERTY_INVALID("看板模型属性不可用"),
        /** MODEL量程缺失、类型错误或边界无效。 */
        MODEL_RANGE_INVALID("看板模型量程不可用"),
        /** 当前生产系统未交付完整精确的数据适配能力。 */
        DATA_ADAPTER_UNAVAILABLE("看板数据适配能力不可用"),
        /** 页面最坏设备、属性、历史或API请求预算超过运行合同。 */
        BUDGET_EXCEEDED("看板页面运行预算超限");

        /** 安全默认消息。 */
        private final String message;

        /** @param message 不泄露外部事实的固定消息 */
        Reason(String message) {
            this.message = message;
        }
    }
}
