package com.things.link.project.domain;

/**
 * 支付渠道（S14-3a）。
 *
 * <p>今天只存在 {@link #SIMULATED}：负责人已确认支付功能暂不开发，微信支付与支付宝只保留渠道，
 * 属 S14-5。数据库侧用 {@code sys_tenant_order_provider_ck CHECK (provider IN ('SIMULATED'))}
 * 钉住「当前只有模拟渠道」，新增真实渠道必须一次显式迁移，连同回调幂等与对账语义一起补齐。
 */
public enum PaymentProvider {

    /** 模拟渠道：金额取修订版参考价，仅验证订单与订阅生命周期，不代表真实销售。 */
    SIMULATED
}
