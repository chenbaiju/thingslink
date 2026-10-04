package com.things.link.support.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 固定通知渠道 bulkhead 验证 PUSH 不借用 EMAIL/WEBHOOK 槽位。 */
class NotificationExternalGuardTests {

    /** PUSH 有独立四槽，释放后可以再次领取。 */
    @Test
    void pushUsesIndependentBoundedBulkhead() {
        NotificationExternalGuard guard = new NotificationExternalGuard();
        List<NotificationExternalGuard.Guard> pushPermits = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            NotificationExternalGuard.Guard permit = guard.tryAcquire("PUSH", "PUSH");
            assertThat(permit).isNotNull();
            pushPermits.add(permit);
        }

        assertThat(guard.tryAcquire("PUSH", "PUSH")).isNull();
        NotificationExternalGuard.Guard emailPermit = guard.tryAcquire("EMAIL", "owner@example.com");
        assertThat(emailPermit).isNotNull();

        pushPermits.forEach(NotificationExternalGuard.Guard::close);
        emailPermit.close();
        NotificationExternalGuard.Guard recovered = guard.tryAcquire("PUSH", "PUSH");
        assertThat(recovered).isNotNull();
        recovered.close();
    }
    /** 提交前取消归还全部本地槽且幂等；不会把该请求记成供应商成功。 */
    @Test
    void abortBeforeSendReturnsEachBulkheadSlotExactlyOnce() {
        NotificationExternalGuard guard = new NotificationExternalGuard();
        List<NotificationExternalGuard.Guard> permits = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                var permit = guard.tryAcquire("EMAIL", "owner@example.com");
                assertThat(permit).isNotNull();
                permits.add(permit);
            }
            assertThat(guard.tryAcquire("EMAIL", "owner@example.com")).isNull();
            permits.getFirst().abortBeforeSend();
            permits.getFirst().abortBeforeSend();
            permits.getFirst().close();
            var replacement = guard.tryAcquire("EMAIL", "owner@example.com");
            assertThat(replacement).isNotNull();
            permits.add(replacement);
            assertThat(guard.tryAcquire("EMAIL", "owner@example.com")).isNull();
        } finally { permits.forEach(NotificationExternalGuard.Guard::abortBeforeSend); }
    }

}
