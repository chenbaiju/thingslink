package com.things.link.support.tenant;

import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式异步租户范围必须精确恢复租户与独立RLS上下文，并隔离复用线程。 */
class ScopedTenantWorkTests {
    /** 每例清除测试线程所有范围。 */
    @AfterEach
    void clear() { TenantContext.clear(); }

    /** 嵌套范围退出后恢复外层，即使RLS本来与租户投影不同。 */
    @Test
    void restoresNestedTenantAndIndependentRlsScope() {
        TenantScope outer = scope();
        TenantScope middle = scope();
        TenantScope inner = scope();
        RlsScope independent = new RlsScope(UUID.randomUUID(), UUID.randomUUID());
        TenantContext.set(outer);
        RlsScopeContext.set(independent);
        assertThat(ScopedTenantWork.call(middle, () -> {
            assertCurrent(middle);
            ScopedTenantWork.run(inner, () -> assertCurrent(inner));
            assertCurrent(middle);
            return "result";
        })).isEqualTo("result");
        assertThat(TenantContext.require()).isEqualTo(outer);
        assertThat(RlsScopeContext.current()).contains(independent);
    }

    /** Runtime异常和Error都不丢失只有RLS、没有控制台身份的调用者范围。 */
    @Test
    void restoresRlsOnlyContextOnExceptionAndError() {
        RlsScope original = new RlsScope(UUID.randomUUID(), null);
        RlsScopeContext.set(original);
        IllegalStateException failure = new IllegalStateException("expected");
        assertThatThrownBy(() -> ScopedTenantWork.call(scope(), () -> { throw failure; })).isSameAs(failure);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).contains(original);
        AssertionError error = new AssertionError("expected");
        assertThatThrownBy(() -> ScopedTenantWork.run(scope(), () -> { throw error; })).isSameAs(error);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).contains(original);
    }

    /** 单线程执行器在成功及异常后复用，也不会把上一租户泄漏给后续工作。 */
    @Test
    void reusableWorkerHasNoResidualIdentityAfterSuccessOrFailure() throws Exception {
        TenantScope caller = scope();
        TenantContext.set(caller);
        try (var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .inheritInheritableThreadLocals(false).factory())) {
            for (int index = 0; index < 2; index++) {
                boolean fail = index == 1;
                executor.submit(() -> {
                    assertThat(TenantContext.current()).isEmpty();
                    assertThat(RlsScopeContext.current()).isEmpty();
                    TenantScope assigned = scope();
                    try {
                        ScopedTenantWork.run(assigned, () -> {
                            assertCurrent(assigned);
                            if (fail) { throw new IllegalStateException("expected"); }
                        });
                    } catch (IllegalStateException exception) {
                        assertThat(fail).isTrue();
                    }
                    assertThat(TenantContext.current()).isEmpty();
                    assertThat(RlsScopeContext.current()).isEmpty();
                }).get(5, TimeUnit.SECONDS);
            }
        }
        assertCurrent(caller);
    }

    /** 原租户存在但RLS被显式清空时，恢复不能擅自补回RLS。 */
    @Test
    void restoresExplicitlyAbsentRlsAlongsideExistingTenant() {
        TenantScope original = scope();
        TenantContext.set(original);
        RlsScopeContext.clear();
        ScopedTenantWork.run(scope(), () -> { });
        assertThat(TenantContext.require()).isEqualTo(original);
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 构造互不共享租户、项目、账号的可信测试身份。 */
    private static TenantScope scope() {
        return new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** 同时核对业务身份及数据面投影。 */
    private static void assertCurrent(TenantScope scope) {
        assertThat(TenantContext.require()).isEqualTo(scope);
        assertThat(RlsScopeContext.current()).contains(new RlsScope(scope.tenantId(), scope.projectId()));
    }
}
