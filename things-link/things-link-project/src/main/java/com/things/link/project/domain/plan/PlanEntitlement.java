package com.things.link.project.domain.plan;

/**
 * 产品修订版的一项功能权益状态。
 *
 * <p>{@code enabled=false}（落库为 {@code DISABLED}）与「额度为 0」是两件事：
 * 前者是未交付或未购买，后者是已交付但明确不提供该量。业务入口只认 capability code，
 * 不能用角色判断或定价表行代替。
 *
 * @param code 冻结 capability code，如 {@code OTA}、{@code SMS_CHANNEL}
 * @param enabled 该档位是否包含且能力已交付
 */
public record PlanEntitlement(String code, boolean enabled) {

    /** capability code 必须是大写闭集编码。 */
    public PlanEntitlement {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("capability code 不合法");
        }
    }
}
