package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S14-3c：宽限期「禁止扩大类动作」门禁的单测（P4）。
 *
 * <p>只替换订阅读取端口，独立断言四种状态分类：GRACE 拒绝（50033）、ACTIVE/RESTRICTED_FREE 放行、
 * 没有订阅事实时不臆造宽限。真实订阅状态由 PG 用例证明。
 */
class SubscriptionExpansionGuardTests {

    /** 当前订阅事实替身。 */
    private final TenantSubscriptionLifecycleRepository repository =
            mock(TenantSubscriptionLifecycleRepository.class);
    /** 被测门禁。 */
    private final SubscriptionExpansionGuard guard = new SubscriptionExpansionGuard(repository);
    /** 固定租户轴。 */
    private final UUID tenantId = UUID.randomUUID();

    /** 宽限期内必须拒绝扩大类动作，错误码是已登记的 50033 而不是额度码 50020。 */
    @Test
    void graceStatusRefusesExpansionWithRegisteredCode() {
        when(repository.findCurrentState(tenantId))
                .thenReturn(Optional.of(state(SubscriptionStatus.GRACE)));

        BusinessException refusal = catchThrowableOfType(
                () -> guard.requireExpansionAllowed(tenantId), BusinessException.class);

        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);
        assertThat(refusal.errorCode().code()).isEqualTo(50033);
        assertThat(refusal.errorCode().httpStatus()).isEqualTo(409);
    }

    /** ACTIVE 与 RESTRICTED_FREE 都放行本门禁：前者续费后恢复，后者由 FREE 档额度接管。 */
    @Test
    void activeAndRestrictedFreeAreNotBlockedByGraceGate() {
        when(repository.findCurrentState(tenantId))
                .thenReturn(Optional.of(state(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(state(SubscriptionStatus.RESTRICTED_FREE)));

        guard.requireExpansionAllowed(tenantId);
        guard.requireExpansionAllowed(tenantId);
    }

    /** 没有订阅事实（存量直插租户）不能把「缺失」当成宽限。 */
    @Test
    void missingSubscriptionDoesNotFabricateGrace() {
        when(repository.findCurrentState(tenantId)).thenReturn(Optional.empty());

        guard.requireExpansionAllowed(tenantId);
    }

    /** 缺少租户身份属于编程前置错误，不能查库猜一个租户。 */
    @Test
    void nullTenantIsProgrammingError() {
        assertThatThrownBy(() -> guard.requireExpansionAllowed(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造一条订阅事实。
     *
     * @param status 订阅状态
     * @return 生命周期事实
     */
    private SubscriptionLifecycleState state(SubscriptionStatus status) {
        return new SubscriptionLifecycleState(UUID.randomUUID(), tenantId, UUID.randomUUID(), status,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                null, null);
    }
}
