package com.things.link.project.application;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 两个独立事务阶段的触发顺序及失败边界；真实事务/状态由 bootstrap PG 回归负责。 */
class SubscriptionLifecycleWorkerTests {
    private final SubscriptionLifecycleService subscriptions = mock(SubscriptionLifecycleService.class);
    private final TenantResourcePackageService packages = mock(TenantResourcePackageService.class);
    private final DeploymentEntitlementPolicy policy = mock(DeploymentEntitlementPolicy.class);
    private final SubscriptionLifecycleWorker worker = new SubscriptionLifecycleWorker(subscriptions, policy, packages);

    @Test
    void advancesSubscriptionBeforePackages() {
        when(subscriptions.advanceDueTransitions()).thenReturn(emptySubscriptions());
        when(packages.advanceDueTransitions()).thenReturn(new ResourcePackageAdvanceReport(1, 1, 2));
        worker.advanceNextBatch();
        var order = inOrder(subscriptions, packages);
        order.verify(subscriptions).advanceDueTransitions();
        order.verify(packages).advanceDueTransitions();
        order.verifyNoMoreInteractions();
    }

    @Test
    void nonCommercialFenceStopsBothStages() {
        when(policy.nonCommercial()).thenReturn(true);
        worker.advanceNextBatch();
        verifyNoInteractions(subscriptions, packages);
    }

    @Test
    void subscriptionFailureDoesNotRunPackagesAndNextTickRetries() {
        when(subscriptions.advanceDueTransitions()).thenThrow(new IllegalStateException("controlled"))
                .thenReturn(emptySubscriptions());
        when(packages.advanceDueTransitions()).thenReturn(new ResourcePackageAdvanceReport(0, 0, 0));
        worker.advanceNextBatch();
        verifyNoInteractions(packages);
        worker.advanceNextBatch();
        var order = inOrder(subscriptions, packages);
        order.verify(subscriptions, org.mockito.Mockito.times(2)).advanceDueTransitions();
        order.verify(packages).advanceDueTransitions();
        order.verifyNoMoreInteractions();
    }

    @Test
    void packageFailureLeavesSubscriptionStageCompletedAndNextTickRetries() {
        when(subscriptions.advanceDueTransitions()).thenReturn(emptySubscriptions());
        when(packages.advanceDueTransitions()).thenThrow(new IllegalStateException("controlled"))
                .thenReturn(new ResourcePackageAdvanceReport(0, 0, 0));
        worker.advanceNextBatch();
        worker.advanceNextBatch();
        var order = inOrder(subscriptions, packages);
        order.verify(subscriptions).advanceDueTransitions();
        order.verify(packages).advanceDueTransitions();
        order.verify(subscriptions).advanceDueTransitions();
        order.verify(packages).advanceDueTransitions();
        order.verifyNoMoreInteractions();
    }

    @Test
    void historicalManualConstructorKeepsSubscriptionOnlyBehavior() {
        when(subscriptions.advanceDueTransitions()).thenReturn(emptySubscriptions());
        new SubscriptionLifecycleWorker(subscriptions).advanceNextBatch();
        verify(subscriptions).advanceDueTransitions();
        verifyNoInteractions(packages);
    }

    private static SubscriptionLifecycleReport emptySubscriptions() {
        return new SubscriptionLifecycleReport(0, 0, 0, 0, 0, 0, 0);
    }
}
