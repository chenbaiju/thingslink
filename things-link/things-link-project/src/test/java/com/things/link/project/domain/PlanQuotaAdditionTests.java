package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * S14-4c 溯源行映射的领域级验收：来源、原因与「此刻是否生效」必须与服务端合成规则逐字一致。
 *
 * <p>期望值独立写死，避免生产实现与断言同时漂移。窗口语义与合成规则同源：只有
 * {@code ACTIVE} 且处于自身 {@code [startsAt, endsAt)} 窗口内的行才算「此刻生效」。
 */
@DisplayName("S14-4c 扩容与调整溯源行")
class PlanQuotaAdditionTests {

    /** 结论时刻。 */
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    /** 人工调整行映射后带原因、且此刻生效。 */
    @Test
    void adjustmentRowKeepsReasonAndIsEffectiveInsideItsWindow() {
        PlanQuotaAddition addition = PlanQuotaAddition.from(packageRow(
                ResourcePackageSource.OPERATION_ADJUSTMENT, ResourcePackageStatus.ACTIVE,
                NOW.minusSeconds(60), NOW.plusSeconds(86_400), "工单 INC-1 补偿"), NOW);

        assertThat(addition.source()).isEqualTo(ResourcePackageSource.OPERATION_ADJUSTMENT);
        assertThat(addition.dimensionCode()).isEqualTo("DEVICES_MAX");
        assertThat(addition.amount()).isEqualTo(2);
        assertThat(addition.unit()).isEqualTo("COUNT");
        assertThat(addition.window()).isEqualTo("NONE");
        assertThat(addition.reason()).isEqualTo("工单 INC-1 补偿");
        assertThat(addition.effectiveNow()).isTrue();
    }

    /** 购买行没有原因（不是人工调整），且同样按窗口判定生效。 */
    @Test
    void purchaseRowHasNoReason() {
        PlanQuotaAddition addition = PlanQuotaAddition.from(packageRow(
                ResourcePackageSource.PURCHASE, ResourcePackageStatus.ACTIVE,
                NOW, NOW.plusSeconds(86_400), null), NOW);

        assertThat(addition.reason()).isNull();
        assertThat(addition.effectiveNow())
                .as("起点恰好等于结论时刻时按闭区间计入（与 SQL 的 starts_at <= at 一致）")
                .isTrue();
    }

    /** 时刻边界与状态共同决定「此刻生效」：未到起点、已过终点、待生效与终态都不算。 */
    @Test
    void effectivenessFollowsStatusAndWindowBoundaries() {
        assertThat(PlanQuotaAddition.from(packageRow(ResourcePackageSource.PURCHASE,
                ResourcePackageStatus.ACTIVE, NOW.plusSeconds(1), NOW.plusSeconds(86_400), null), NOW)
                .effectiveNow()).as("未来起点不提前生效").isFalse();
        assertThat(PlanQuotaAddition.from(packageRow(ResourcePackageSource.PURCHASE,
                ResourcePackageStatus.ACTIVE, NOW.minusSeconds(86_400), NOW, null), NOW)
                .effectiveNow()).as("终点不含（ends_at > at 才生效）").isFalse();
        assertThat(PlanQuotaAddition.from(packageRow(ResourcePackageSource.OPERATION_ADJUSTMENT,
                ResourcePackageStatus.PENDING, NOW.minusSeconds(60), NOW.plusSeconds(86_400), "待生效"), NOW)
                .effectiveNow()).as("PENDING 不参与合成").isFalse();
        assertThat(PlanQuotaAddition.from(packageRow(ResourcePackageSource.OPERATION_ADJUSTMENT,
                ResourcePackageStatus.EXPIRED, NOW.minusSeconds(86_400), NOW.minusSeconds(60), "已到期"), NOW)
                .effectiveNow()).as("EXPIRED 不参与合成").isFalse();
    }

    /** 来源与原因同生共死：人工调整必须带原因，购买不得带原因。 */
    @Test
    void sourceAndReasonMustAgree() {
        assertThatThrownBy(() -> new PlanQuotaAddition(ResourcePackageSource.OPERATION_ADJUSTMENT,
                "DEVICES_MAX", 2, "COUNT", "NONE", NOW, NOW.plusSeconds(60),
                ResourcePackageStatus.ACTIVE, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("人工调整");
        assertThatThrownBy(() -> new PlanQuotaAddition(ResourcePackageSource.PURCHASE,
                "DEVICES_MAX", 2, "COUNT", "NONE", NOW, NOW.plusSeconds(60),
                ResourcePackageStatus.ACTIVE, true, "不该有原因"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("购买包");
        assertThatThrownBy(() -> new PlanQuotaAddition(ResourcePackageSource.PURCHASE,
                "DEVICES_MAX", 0, "COUNT", "NONE", NOW, NOW.plusSeconds(60),
                ResourcePackageStatus.ACTIVE, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanQuotaAddition(ResourcePackageSource.PURCHASE,
                "DEVICES_MAX", 2, "COUNT", "NONE", NOW, NOW,
                ResourcePackageStatus.ACTIVE, true, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> new PlanQuotaAddition(ResourcePackageSource.PURCHASE,
                "DEVICES_MAX", 2, "COUNT", "NONE", NOW, NOW.plusSeconds(60),
                ResourcePackageStatus.ACTIVE, true, null)).doesNotThrowAnyException();
    }

    /**
     * 构造一条用于映射校验的资源包事实。
     *
     * @param source 来源
     * @param status 状态
     * @param startsAt 起点
     * @param endsAt 终点
     * @param reason 调整原因；购买包必须为 {@code null}
     * @return 资源包事实
     */
    private static TenantResourcePackage packageRow(ResourcePackageSource source,
                                                    ResourcePackageStatus status,
                                                    Instant startsAt, Instant endsAt, String reason) {
        ResourcePackageAdjustment adjustment = reason == null ? null
                : new ResourcePackageAdjustment(reason, UUID.randomUUID(), "ticket-0001");
        return new TenantResourcePackage(UUID.randomUUID(), UUID.randomUUID(), "DEVICES_MAX", 2,
                "COUNT", "NONE", startsAt, endsAt, source,
                source == ResourcePackageSource.PURCHASE ? UUID.randomUUID() : null, adjustment,
                status, 1);
    }
}
