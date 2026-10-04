package com.things.link.project.domain.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-4a 有效权益合成的领域级验收：有效值 = 基础档 + 同维度/同单位/同窗口的有效包加数。
 *
 * <p>期望值在本测试里独立写死（基础模板 3 台设备 → 加 2 个包 → 7），而不是复用生产常量，
 * 这样两边同时改错时才会被发现。
 */
@DisplayName("S14-4a 资源包有效权益合成")
class EffectivePlanQuotaCompositionTests {

    /** 基础档模板：设备 3、项目 1、上行 700；其它维度取确定的小值。 */
    private static final PlanQuotaTemplate BASE = new PlanQuotaTemplate(
            1, 3, 3, 1, 0, "DAY", 7, 700, 300, 10_000, 60, 10, 1, 10_000, 1_000, 1_000, 100L * 1024 * 1024);

    /** @return 一个以 FREE 基础模板为底、没有资源包的三参投影 */
    private static EffectivePlanQuota baseQuota() {
        return new EffectivePlanQuota(UUID.randomUUID(), 7L, BASE);
    }

    /** 买设备包后设备上限 = 基础 + 包，其它维度不受影响；基础模板本身不被改写。 */
    @Test
    void packageOnOneDimensionRaisesOnlyThatDimension() {
        EffectivePlanQuota composed = EffectivePlanQuota.compose(baseQuota(),
                List.of(new ResourcePackageAddition("DEVICES_MAX", "COUNT", "NONE", 2)));

        assertThat(composed.devicesMax()).isEqualTo(5);
        assertThat(composed.projectsMax()).isEqualTo(1);
        assertThat(composed.uplinkMessageDailyLimit()).isEqualTo(700);
        assertThat(composed.hasPackageAdditions()).isTrue();
        assertThat(composed.packageAdditions()).containsExactly(Map.entry("DEVICES_MAX", 2L));
        assertThat(composed.frozenTemplate()).isEqualTo(BASE);
        assertThat(composed.template()).isEqualTo(BASE);
    }

    /** 同维度两个包求和（同一窗口内的两笔购买）。 */
    @Test
    void twoPackagesOnSameDimensionSum() {
        EffectivePlanQuota composed = EffectivePlanQuota.compose(baseQuota(), List.of(
                new ResourcePackageAddition("UPLINK_MESSAGE_DAILY", "MESSAGE", "UTC_DAY", 300),
                new ResourcePackageAddition("UPLINK_MESSAGE_DAILY", "MESSAGE", "UTC_DAY", 500)));

        assertThat(composed.uplinkMessageDailyLimit()).isEqualTo(700 + 800);
        assertThat(composed.downlinkMessageDailyLimit()).isEqualTo(300);
    }

    /** 单位或窗口不一致的加数不参与求和（不得把「次/日」当成「次/分钟」）。 */
    @Test
    void additionsWithMismatchedUnitOrWindowAreIgnored() {
        EffectivePlanQuota composed = EffectivePlanQuota.compose(baseQuota(), List.of(
                new ResourcePackageAddition("UPLINK_MESSAGE_DAILY", "REQUEST", "UTC_DAY", 999),
                new ResourcePackageAddition("UPLINK_MESSAGE_DAILY", "MESSAGE", "MINUTE", 999),
                new ResourcePackageAddition("UNKNOWN_DIMENSION", "COUNT", "NONE", 999)));

        assertThat(composed.uplinkMessageDailyLimit()).isEqualTo(700);
        assertThat(composed.hasPackageAdditions()).isFalse();
    }

    /** 没有可用加数时返回基础投影本身（对象恒等），避免无谓的缓存条目变化。 */
    @Test
    void noMatchingAdditionReturnsTheBaseProjection() {
        EffectivePlanQuota base = baseQuota();
        assertThat(EffectivePlanQuota.compose(base, List.of())).isSameAs(base);
        assertThat(EffectivePlanQuota.compose(base,
                List.of(new ResourcePackageAddition("DEVICES_MAX", "COUNT", "MINUTE", 5)))).isSameAs(base);
    }

    /** 历史窗口的单位随基础模板变化：只有与基础单位相同的加数才生效。 */
    @Test
    void historyWindowAdditionMustMatchBaseUnit() {
        EffectivePlanQuota months = EffectivePlanQuota.compose(
                new EffectivePlanQuota(UUID.randomUUID(), 1L,
                        new PlanQuotaTemplate(1, 3, 3, 1, 0, "MONTH", 6, 700, 300, 10_000, 60, 10, 1,
                                10_000, 1_000, 1_000, 100L * 1024 * 1024)),
                List.of(new ResourcePackageAddition("HISTORY_WINDOW", "MONTH", "ROLLING", 3),
                        new ResourcePackageAddition("HISTORY_WINDOW", "DAY", "ROLLING", 30)));

        assertThat(months.historyWindowUnit()).isEqualTo("MONTH");
        assertThat(months.historyWindowAmount()).isEqualTo(9);
    }

    /** S14-4c：运行时有效维度覆盖全部 16 个登记维度，单位/窗口按运行时口径，数值已含加数。 */
    @Test
    void effectiveDimensionsRenderEveryRuntimeDimensionInRuntimeUnits() {
        EffectivePlanQuota composed = EffectivePlanQuota.compose(baseQuota(),
                List.of(new ResourcePackageAddition("DEVICES_MAX", "COUNT", "NONE", 2),
                        new ResourcePackageAddition("UPLINK_MESSAGE_DAILY", "MESSAGE", "UTC_DAY", 300)));

        List<PlanDimension> dimensions = composed.effectiveDimensions();

        assertThat(dimensions).hasSize(ResourcePackageDimension.values().length).hasSize(16);
        assertThat(dimensions).extracting(PlanDimension::code).isSorted();
        assertThat(dimension(dimensions, "DEVICES_MAX")).isEqualTo(
                new PlanDimension("DEVICES_MAX", 5, "COUNT", "NONE"));
        assertThat(dimension(dimensions, "UPLINK_MESSAGE_DAILY")).isEqualTo(
                new PlanDimension("UPLINK_MESSAGE_DAILY", 1_000, "MESSAGE", "UTC_DAY"));
        assertThat(dimension(dimensions, "PROJECTS_MAX")).isEqualTo(
                new PlanDimension("PROJECTS_MAX", 1, "COUNT", "NONE"));
        // 存储按运行时字节口径，而不是目录的 MB/GB 表示；两者不得互相冒充。
        assertThat(dimension(dimensions, "STORAGE_LIMIT")).isEqualTo(
                new PlanDimension("STORAGE_LIMIT", 100L * 1024 * 1024, "BYTE", "NONE"));
        // 历史窗口单位取自模板本身。
        assertThat(dimension(dimensions, "HISTORY_WINDOW")).isEqualTo(
                new PlanDimension("HISTORY_WINDOW", 7, "DAY", "ROLLING"));
    }

    /** 没有加数时运行时有效维度就是基础档数值（合成规则不因展示而改变）。 */
    @Test
    void effectiveDimensionsEqualFrozenValuesWhenNoAdditionApplies() {
        EffectivePlanQuota base = baseQuota();
        List<PlanDimension> dimensions = base.effectiveDimensions();

        assertThat(base.hasPackageAdditions()).isFalse();
        assertThat(dimension(dimensions, "DEVICES_MAX"))
                .isEqualTo(new PlanDimension("DEVICES_MAX", 3, "COUNT", "NONE"));
        assertThat(dimension(dimensions, "UPLINK_MESSAGE_DAILY"))
                .isEqualTo(new PlanDimension("UPLINK_MESSAGE_DAILY", 700, "MESSAGE", "UTC_DAY"));
        assertThat(dimension(dimensions, "EXTERNAL_COLLABORATOR_SEATS"))
                .isEqualTo(new PlanDimension("EXTERNAL_COLLABORATOR_SEATS", 0, "COUNT", "NONE"));
        assertThat(dimension(dimensions, "HISTORY_WINDOW"))
                .isEqualTo(new PlanDimension("HISTORY_WINDOW", 7, "DAY", "ROLLING"));
    }

    /**
     * 在维度列表里按编码定位。
     *
     * @param dimensions 维度列表
     * @param code 目标编码
     * @return 目标维度
     */
    private static PlanDimension dimension(List<PlanDimension> dimensions, String code) {
        return dimensions.stream().filter(entry -> entry.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少维度 " + code));
    }

    /** 加数必须为正、维度编码必须来自封闭登记表。 */
    @Test
    void invalidAdditionsAreRejected() {
        assertThatThrownBy(() -> new ResourcePackageAddition("DEVICES_MAX", "COUNT", "NONE", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ResourcePackageDimension.fromCode("NOT_A_DIMENSION")).isEmpty();
        assertThat(ResourcePackageDimension.fromCode("DEVICES_MAX")).isPresent();
        assertThat(ResourcePackageDimension.HISTORY_WINDOW.fixedUnit()).isNull();
        assertThat(ResourcePackageDimension.HISTORY_WINDOW.platformHardLimit()).isEmpty();
    }
}
