package com.things.link.telemetry.application;

import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository.BackfillWindow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证聚合回补只在刷新、对账与 revision 三重收敛后记成功。 */
class PropertyAggregateBackfillScannerTests {

    /** 对账为零且 revision 稳定时按 refresh→verify→complete 顺序完成。 */
    @Test
    void completesOnlyAfterRefreshAndReconciliation() {
        PropertyAggregateBackfillRepository repository = mock(PropertyAggregateBackfillRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PropertyAggregateBackfillScanner scanner = scanner(repository, registry);
        BackfillWindow window = window();
        when(repository.claimDue(4)).thenReturn(List.of(window));
        when(repository.mismatchCount(window)).thenReturn(0L);
        when(repository.complete(window)).thenReturn(true);

        scanner.scan();

        var order = inOrder(repository);
        order.verify(repository).refresh(window);
        order.verify(repository).mismatchCount(window);
        order.verify(repository).complete(window);
        assertThat(counter(registry, "success")).isEqualTo(1.0);
        assertThat(counter(registry, "failure")).isZero();
    }

    /** 对账差异不得删除请求，并通过失败指标与有界退避进入下一轮。 */
    @Test
    void retriesAndSignalsWhenReconciliationStillDiffers() {
        PropertyAggregateBackfillRepository repository = mock(PropertyAggregateBackfillRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PropertyAggregateBackfillScanner scanner = scanner(repository, registry);
        BackfillWindow window = window();
        when(repository.mismatchCount(window)).thenReturn(2L);

        scanner.process(window);

        verify(repository, never()).complete(window);
        verify(repository).fail(window, "IllegalStateException");
        assertThat(counter(registry, "success")).isZero();
        assertThat(counter(registry, "failure")).isEqualTo(1.0);
    }

    /** 在途刷新期间 revision 变化表示又有新迟到点，旧领取不能冒充成功。 */
    @Test
    void leavesRevisedWindowForAnotherPass() {
        PropertyAggregateBackfillRepository repository = mock(PropertyAggregateBackfillRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PropertyAggregateBackfillScanner scanner = scanner(repository, registry);
        BackfillWindow window = window();
        when(repository.mismatchCount(window)).thenReturn(0L);
        when(repository.complete(window)).thenReturn(false);

        scanner.process(window);

        verify(repository, never()).fail(window, "IllegalStateException");
        assertThat(counter(registry, "success")).isZero();
        assertThat(counter(registry, "failure")).isZero();
    }

    /** @return 固定单日窗口 */
    private static BackfillWindow window() {
        return new BackfillWindow(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-21T00:00:00Z"), 3L);
    }

    /** @return 使用真实低基数指标的扫描器 */
    private static PropertyAggregateBackfillScanner scanner(
            PropertyAggregateBackfillRepository repository, SimpleMeterRegistry registry) {
        return new PropertyAggregateBackfillScanner(repository, new PropertyAggregateBackfillMetrics(registry));
    }

    /** @return 指定固定结果计数 */
    private static double counter(SimpleMeterRegistry registry, String result) {
        return registry.get(PropertyAggregateBackfillMetrics.BACKFILLS)
                .tag("result", result).counter().count();
    }
}
