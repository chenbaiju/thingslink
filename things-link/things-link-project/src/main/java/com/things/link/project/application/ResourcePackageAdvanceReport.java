package com.things.link.project.application;

/**
 * 资源包时间推进一轮的结果计数（S14-4a）。
 *
 * <p>用于运维与用例断言「重跑同一时刻不产生新的变化」：三段计数都应为 0，
 * 且受影响租户数为 0。
 *
 * @param expired 本轮由 {@code ACTIVE} 置为 {@code EXPIRED} 的包数
 * @param activatedPending 本轮由 {@code PENDING} 置为 {@code ACTIVE} 的包数
 * @param tenantsInvalidated 本轮因包变化而复用既有缓存失效协议的租户数
 */
public record ResourcePackageAdvanceReport(
        int expired,
        int activatedPending,
        int tenantsInvalidated) {
}
