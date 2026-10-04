package com.things.link.shared.tenant;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("租户上下文")
class TenantContextTests {

    private static final UUID TENANT_ID = Uuid7.generate();
    private static final UUID PROJECT_ID = Uuid7.generate();
    private static final UUID ACCOUNT_ID = Uuid7.generate();

    @AfterEach
    void tearDown() {
        // 测试之间必须清理，否则 ThreadLocal 会串到下一个测试 ——
        // 这正是生产上跨租户泄露的同一个机制
        TenantContext.clear();
    }

    @Test
    @DisplayName("绑定后可读取")
    void bindsAndReads() {
        TenantContext.set(new TenantScope(TENANT_ID, PROJECT_ID, ACCOUNT_ID));

        assertThat(TenantContext.require().tenantId()).isEqualTo(TENANT_ID);
        assertThat(TenantContext.requireProjectId()).isEqualTo(PROJECT_ID);
    }

    /**
     * 拿不到租户范围时必须立刻失败，而不是继续执行一个没有租户过滤的查询 ——
     * 后者是跨租户数据泄露。
     */
    @Test
    @DisplayName("未绑定时 require 抛异常而非返回 null")
    void failsFastWhenUnbound() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有租户上下文");
    }

    @Test
    @DisplayName("未选定项目时访问项目级数据抛异常")
    void failsWhenProjectNotSelected() {
        TenantContext.set(new TenantScope(TENANT_ID, null, ACCOUNT_ID));

        assertThat(TenantContext.require().tenantId()).isEqualTo(TENANT_ID);
        assertThatThrownBy(TenantContext::requireProjectId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未选定项目");
    }

    @Test
    @DisplayName("clear 之后不再可读")
    void clearsBinding() {
        TenantContext.set(new TenantScope(TENANT_ID, PROJECT_ID, ACCOUNT_ID));
        TenantContext.clear();

        assertThat(TenantContext.current()).isEmpty();
    }

    /**
     * ThreadLocal 不跨线程传播。这条断言不是在测试 JDK，而是把「异步任务必须从
     * 消息信封显式恢复租户范围」这个约束固化下来 —— 将来有人给 TenantContext 换成
     * InheritableThreadLocal「顺手解决异步问题」时，这个测试会红。
     */
    @Test
    @DisplayName("上下文不会泄漏到其他线程")
    void doesNotPropagateToOtherThreads() throws Exception {
        TenantContext.set(new TenantScope(TENANT_ID, PROJECT_ID, ACCOUNT_ID));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> otherThreadHasScope = executor.submit(
                    () -> TenantContext.current().isPresent());

            assertThat(otherThreadHasScope.get())
                    .as("异步任务必须显式恢复租户范围，不能依赖继承")
                    .isFalse();
        }
    }

}
