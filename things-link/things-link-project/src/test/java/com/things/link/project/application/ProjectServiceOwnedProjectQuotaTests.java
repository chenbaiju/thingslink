package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectRegion;
import com.things.link.project.domain.ProjectRegionRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.plan.EffectivePlanQuota;
import com.things.link.project.domain.plan.ProductRevision1;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-2b：项目创建的自有项目数守卫单元测试。
 *
 * <p>只钉住服务内的判定与调用顺序：FREE 冻结 {@code projects_max = 1} 时第二个自有项目被 50020 拒绝；
 * 没有冻结投影的 S7 旧模板不放行臆造上限；租户锁必须先于计数取得。原子性与真实 PG 决策由 bootstrap
 * 的真库用例承担，这里不伪造数据库行为。
 */
class ProjectServiceOwnedProjectQuotaTests {

    /** 请求租户即项目归属租户。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 创建者账号。 */
    private final UUID accountId = UUID.randomUUID();
    /** 项目仓储替身，用于捕获锁与写入顺序。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 区域目录替身，放行 sh-1。 */
    private final ProjectRegionRepository regions = mock(ProjectRegionRepository.class);
    /** 有效套餐读取替身。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider = mock(EffectiveQuotaPolicyProvider.class);
    /** 被测生产服务。 */
    private final ProjectService service =
            new ProjectService(repository, regions, mock(ProjectManagementWriteGuard.class), quotaPolicyProvider,
                    mock(SubscriptionExpansionGuard.class));

    /** 建立租户范围与可创建区域。 */
    @BeforeEach
    void prepare() {
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        when(regions.findProjectCreatable("sh-1")).thenReturn(Optional.of(
                new ProjectRegion("sh-1", "上海一", "sh", "上海", true, true, 1)));
    }

    /** 清理线程范围。 */
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** FREE 冻结 1 个自有项目：已有 1 个时第二个以 50020 拒绝，且不落库。 */
    @Test
    void refusesSecondOwnedProjectWhenFrozenProjectsMaxIsOne() {
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(policyWithProjectsMax("FREE", 1L));
        when(repository.countOwnedProjects(tenantId)).thenReturn(1);

        BusinessException exception = catchThrowableOfType(
                () -> service.create("第二个项目", "sh-1", null), BusinessException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_QUOTA_EXCEEDED);
        assertThat(exception.errorCode().httpStatus()).isEqualTo(429);
        verify(repository, never()).create(any(Project.class));
    }

    /** 第一个自有项目在 FREE 下必须放行。 */
    @Test
    void allowsFirstOwnedProjectWhenFrozenProjectsMaxIsOne() {
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(policyWithProjectsMax("FREE", 1L));
        when(repository.countOwnedProjects(tenantId)).thenReturn(0);

        service.create("第一个项目", "sh-1", null);

        verify(repository).create(any(Project.class));
    }

    /** 付费档冻结更高上限时，第二个自有项目不再被拒。 */
    @Test
    void allowsSecondOwnedProjectUnderPaidTemplate() {
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(policyWithProjectsMax("STANDARD", 5L));
        when(repository.countOwnedProjects(tenantId)).thenReturn(1);

        service.create("第二个项目", "sh-1", null);

        verify(repository).create(any(Project.class));
    }

    /** 缺少冻结投影时不能以未知代替无限；返回独立503且零写入。 */
    @Test
    void refusesCreationWhenPolicyHasNoFrozenPlanQuota() {
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId));

        BusinessException failure = catchThrowableOfType(
                () -> service.create("旧模板项目", "sh-1", null), BusinessException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.errorCode().code()).isEqualTo(50047);
        assertThat(failure.errorCode().httpStatus()).isEqualTo(503);
        verify(repository, never()).create(any(Project.class));
        verify(repository, never()).countOwnedProjects(any(UUID.class));
    }

    /** 锁必须早于计数：否则并发事务会同时读到「未到上限」。 */
    @Test
    void acquiresTenantLockBeforeCountingOwnedProjects() {
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(policyWithProjectsMax("FREE", 1L));
        when(repository.countOwnedProjects(tenantId)).thenReturn(0);

        service.create("锁序项目", "sh-1", null);

        InOrder order = inOrder(repository);
        order.verify(repository).lockTenantProjectQuota(tenantId);
        order.verify(repository).countOwnedProjects(tenantId);
    }

    /**
     * 用产品冻结模板构造有效策略，避免在测试里手抄额度数值。
     *
     * @param planCode 产品档位编码
     * @param expectedProjectsMax 期望的冻结自有项目数上限
     * @return 携带冻结配额的有效策略
     */
    private EffectiveQuotaPolicy policyWithProjectsMax(String planCode, long expectedProjectsMax) {
        EffectivePlanQuota planQuota = new EffectivePlanQuota(UUID.randomUUID(), 1L,
                ProductRevision1.quotaTemplates().get(planCode));
        assertThat(planQuota.projectsMax()).isEqualTo(expectedProjectsMax);
        return EffectiveQuotaPolicy.safeDefault(tenantId).withPlanQuota(planQuota);
    }
}
