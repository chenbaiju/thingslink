package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-4b 人工调整领域级验收：元数据必须齐全可追溯，来源与来源元数据必须同生共死。
 *
 * <p>这里只钉住域类型自身的不可变式（与数据库 CHECK 逐条对应），不涉及合成——合成由
 * {@code EffectivePlanQuotaCompositionTests}（与购买包共用同一条规则）和真库用例负责。
 * 期望值独立写死，避免生产常量与断言同时漂移。
 */
@DisplayName("S14-4b 人工调整元数据")
class ResourcePackageAdjustmentTests {

    /** 一个合法操作人。 */
    private static final UUID OPERATOR = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 长度上限与数据库列宽一致：改列宽必须同时改这里，否则真库插入才会失败。 */
    @Test
    void lengthBoundsMatchDatabaseColumns() {
        assertThat(ResourcePackageAdjustment.MAX_REASON_LENGTH).isEqualTo(512);
        assertThat(ResourcePackageAdjustment.MAX_IDEMPOTENCY_KEY_LENGTH).isEqualTo(128);
        assertThat(ResourcePackageAdjustment.MIN_IDEMPOTENCY_KEY_LENGTH).isEqualTo(8);
    }

    /** 完整元数据被逐字保留（原因不做除首尾空白外的任何改写）。 */
    @Test
    void completeMetadataIsPreserved() {
        ResourcePackageAdjustment adjustment =
                new ResourcePackageAdjustment("工单 INC-2026-0916 补偿", OPERATOR, "inc-20260916-1");

        assertThat(adjustment.reason()).isEqualTo("工单 INC-2026-0916 补偿");
        assertThat(adjustment.operatorId()).isEqualTo(OPERATOR);
        assertThat(adjustment.idempotencyKey()).isEqualTo("inc-20260916-1");
    }

    /** 原因为空或空白串被拒：空白与缺失在「谁批准了这份额度」上是同一个答案。 */
    @Test
    void blankReasonIsRejected() {
        assertThatThrownBy(() -> new ResourcePackageAdjustment(null, OPERATOR, "inc-20260916-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原因");
        assertThatThrownBy(() -> new ResourcePackageAdjustment("   ", OPERATOR, "inc-20260916-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原因");
    }

    /** 原因超长被拒（不得超过 512 字符）。 */
    @Test
    void oversizedReasonIsRejected() {
        String tooLong = "x".repeat(ResourcePackageAdjustment.MAX_REASON_LENGTH + 1);

        assertThatThrownBy(() -> new ResourcePackageAdjustment(tooLong, OPERATOR, "inc-20260916-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 操作人必填：没有操作人的调整无法追责。 */
    @Test
    void operatorIsRequired() {
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", null, "inc-20260916-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作人");
    }

    /** 幂等键形状与数据库 CHECK 一致：长度 8～128、以字母或数字开头、字符集受限。 */
    @Test
    void idempotencyKeyShapeIsEnforced() {
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", OPERATOR, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", OPERATOR, "short7x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", OPERATOR, "-starts-with-dash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", OPERATOR, "has space in key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourcePackageAdjustment("补偿", OPERATOR,
                "k".repeat(ResourcePackageAdjustment.MAX_IDEMPOTENCY_KEY_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ResourcePackageAdjustment("补偿", OPERATOR, "a".repeat(8)).idempotencyKey())
                .hasSize(8);
        assertThat(new ResourcePackageAdjustment("补偿", OPERATOR,
                "k".repeat(ResourcePackageAdjustment.MAX_IDEMPOTENCY_KEY_LENGTH)).idempotencyKey())
                .hasSize(ResourcePackageAdjustment.MAX_IDEMPOTENCY_KEY_LENGTH);
    }

    /** 人工调整必须携带元数据且不得携带订单；购买必须携带订单且不得携带元数据。 */
    @Test
    void sourceAndSourceMetadataMustAgree() {
        assertThatThrownBy(() -> packageRow(ResourcePackageSource.OPERATION_ADJUSTMENT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("人工调整");
        assertThatThrownBy(() -> packageRow(ResourcePackageSource.OPERATION_ADJUSTMENT, UUID.randomUUID(),
                new ResourcePackageAdjustment("补偿", OPERATOR, "inc-20260916-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("订单");
        assertThatThrownBy(() -> packageRow(ResourcePackageSource.PURCHASE, UUID.randomUUID(),
                new ResourcePackageAdjustment("补偿", OPERATOR, "inc-20260916-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("购买");
        assertThatThrownBy(() -> packageRow(ResourcePackageSource.PURCHASE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("订单");

        TenantResourcePackage adjustmentRow = packageRow(ResourcePackageSource.OPERATION_ADJUSTMENT, null,
                new ResourcePackageAdjustment("补偿", OPERATOR, "inc-20260916-1"));
        assertThat(adjustmentRow.sourceOrderId()).isNull();
        assertThat(adjustmentRow.adjustment()).isNotNull();

        TenantResourcePackage purchaseRow = packageRow(ResourcePackageSource.PURCHASE, UUID.randomUUID(), null);
        assertThat(purchaseRow.sourceOrderId()).isNotNull();
        assertThat(purchaseRow.adjustment()).isNull();
    }

    /**
     * 构造一行用于校验组合约束的包事实。
     *
     * @param source 来源
     * @param sourceOrderId 来源订单 ID
     * @param adjustment 调整元数据
     * @return 包事实
     */
    private static TenantResourcePackage packageRow(ResourcePackageSource source, UUID sourceOrderId,
                                                    ResourcePackageAdjustment adjustment) {
        return new TenantResourcePackage(UUID.randomUUID(), UUID.randomUUID(), "DEVICES_MAX", 2,
                "COUNT", "NONE", Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-10-16T00:00:00Z"), source, sourceOrderId, adjustment,
                ResourcePackageStatus.ACTIVE, 1);
    }
}
