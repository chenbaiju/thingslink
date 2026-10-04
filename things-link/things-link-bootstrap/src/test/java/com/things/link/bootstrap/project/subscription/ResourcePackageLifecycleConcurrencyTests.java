package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.EntitlementAdjustmentRequest;
import com.things.link.project.application.ResourcePackageAdvanceReport;
import com.things.link.project.application.TenantEntitlementAdjustmentService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantRefundService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/** R4-4a：真实服务/PG锁竞争；观察器只记录真实事务PID，不替换查询或制造业务结果。 */
// 本类最多同时使用持锁会话、两名推进者和独立PG观察者；不改变生产池或锁超时。
@TestPropertySource(properties = "things-link.datasource.control.maximum-pool-size=6")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourcePackageLifecycleConcurrencyTests extends AbstractIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private TenantProvisioning tenants;
    @Autowired private TenantResourcePackageService packages;
    @Autowired private TenantRefundService refunds;
    @Autowired private TenantEntitlementAdjustmentService adjustments;
    @MockitoSpyBean private TenantResourcePackageRepository packageRows;
    private final Set<UUID> ownedTenants = new LinkedHashSet<>();
    private final ThreadLocal<String> probe = new ThreadLocal<>();
    private final Map<String, Integer> pids = new ConcurrentHashMap<>();

    @BeforeEach
    void observeRealTransaction() {
        doAnswer(call -> {
            observe();
            return call.callRealMethod();
        }).when(packageRows).findDueForExpiry(any(), anyInt());
        doAnswer(call -> {
            observe();
            return call.callRealMethod();
        }).when(packageRows).findById(any());
    }

    private void observe() {
        String name = probe.get();
        if (name == null || pids.containsKey(name)) return;
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        jdbc.execute("SET LOCAL lock_timeout = '8s'");
        pids.put(name, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

    /** 退款持有租户锁时，推进不能先锁包再反向等待租户；两次真实事务均须成功。 */
    @Test
    void expiryWaitsForRefundTenantLockWithoutLockingPackageFirst() throws Exception {
        UUID tenant = tenant("退款与到期");
        Purchased purchased = buy(tenant);
        expireFixture(purchased.id(), Instant.now().minusSeconds(1));
        var advancing = new AtomicReference<Future<Outcome<ResourcePackageAdvanceReport>>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Outcome<Object> refund = capture(() -> transactions.execute(status -> {
                int holder = lockTenant(tenant);
                advancing.set(executor.submit(() -> named("advance", () -> packages.advance(Instant.now()))));
                awaitBlockedBy("advance", holder);
                return refunds.refundPackageOrder(tenant, purchased.order().id(), purchased.order().amountCents(),
                        "R4 lock ordering", "refund-" + tenant);
            }));
            Outcome<ResourcePackageAdvanceReport> advanced = advancing.get().get(15, TimeUnit.SECONDS);
            assertThat(refund.failure()).as("退款不得成为逆锁序死锁的受害事务").isNull();
            assertThat(advanced.failure()).as("推进不得成为逆锁序死锁的受害事务").isNull();
            assertThat(advanced.value().expired()).isZero();
        }
        assertThat(state(purchased.id())).isEqualTo("REFUNDED");
        assertThat(count(tenant, "commercial.refund.succeeded")).isEqualTo(1);
        assertThat(count(tenant, "commercial.package.expired")).isZero();
        assertThat(packages.advance(Instant.now()).expired()).isZero();
        refunds.refundPackageOrder(tenant, purchased.order().id(), purchased.order().amountCents(),
                "R4 replay", "refund-" + tenant);
        assertThat(count(tenant, "commercial.refund.succeeded")).isEqualTo(1);
    }

    /** 候选按到期日反序排列；两个推进事务仍须按同一UUID顺序先锁全部租户。 */
    @Test
    void competingWorkersLockAllCandidateTenantsInUuidOrderAndExpireOnce() throws Exception {
        var ordered = new ArrayList<>(List.of(tenant("排序甲"), tenant("排序乙")));
        ordered.sort(UUID::compareTo);
        UUID low = ordered.get(0), high = ordered.get(1);
        Purchased lowPackage = buy(low), highPackage = buy(high);
        Instant now = Instant.now();
        expireFixture(lowPackage.id(), now.minusSeconds(1));
        expireFixture(highPackage.id(), now.minusSeconds(2));
        List<Future<Outcome<ResourcePackageAdvanceReport>>> pending = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Outcome<Object> held = capture(() -> transactions.execute(status -> {
                int holder = lockTenant(low);
                pending.add(executor.submit(() -> named("first", () -> packages.advance(now))));
                awaitBlockedBy("first", holder);
                pending.add(executor.submit(() -> named("second", () -> packages.advance(now))));
                awaitBlockedBy("second", holder);
                // 等待最小UUID租户时不得持有后续租户/包锁；NOWAIT提供数据库事实而非睡眠推测。
                jdbc.queryForObject("SELECT id FROM sys_tenant WHERE id = ? FOR UPDATE NOWAIT", UUID.class, high);
                jdbc.queryForList("SELECT id FROM sys_tenant_resource_package WHERE tenant_id IN (?, ?) FOR UPDATE NOWAIT",
                        UUID.class, low, high);
                return null;
            }));
            List<Outcome<ResourcePackageAdvanceReport>> results = new ArrayList<>();
            for (var future : pending) results.add(future.get(15, TimeUnit.SECONDS));
            assertThat(held.failure()).as("两个真实事务均先等待最小UUID租户").isNull();
            assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result.failure()).isNull());
            assertThat(results.stream().mapToInt(result -> result.value().expired()).sum()).isEqualTo(2);
            assertThat(results.stream().mapToInt(result -> result.value().tenantsInvalidated()).sum()).isEqualTo(2);
        }
        assertThat(state(lowPackage.id())).isEqualTo("EXPIRED");
        assertThat(state(highPackage.id())).isEqualTo("EXPIRED");
        assertThat(count(low, "commercial.package.expired")).isEqualTo(1);
        assertThat(count(high, "commercial.package.expired")).isEqualTo(1);
    }

    /** 撤销不能先锁包；租户锁等待后须重读已由推进置为EXPIRED的事实，保持既有409合同。 */
    @Test
    void adjustmentRevocationRechecksTerminalStateAfterWaitingForTenant() throws Exception {
        UUID tenant = tenant("调整撤销与到期");
        UUID operator = UUID.randomUUID();
        Instant now = Instant.now();
        var adjustment = adjustments.createAdjustment(tenant, new EntitlementAdjustmentRequest("DEVICES_MAX", 2,
                now.minusSeconds(60), now.plus(Duration.ofDays(1)), "R4 adjustment", operator, "adjust-" + tenant));
        var revoking = new AtomicReference<Future<Outcome<Boolean>>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Outcome<Object> advanced = capture(() -> transactions.execute(status -> {
                int holder = lockTenant(tenant);
                revoking.set(executor.submit(() -> named("revoke", () -> adjustments.revokeAdjustment(
                        tenant, adjustment.adjustmentId(), operator, "R4 revoke"))));
                awaitBlockedBy("revoke", holder);
                // 显式未来时间只用于状态机竞争，不宣称真实自然到期。
                return packages.advance(now.plus(Duration.ofDays(2)));
            }));
            Outcome<Boolean> revoked = revoking.get().get(15, TimeUnit.SECONDS);
            assertThat(advanced.failure()).as("到期推进不得与撤销形成逆锁序").isNull();
            assertThat(revoked.failure()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) revoked.failure()).errorCode()).isEqualTo(ProjectErrorCode.ADJUSTMENT_NOT_REVOCABLE);
        }
        assertThat(state(adjustment.adjustmentId())).isEqualTo("EXPIRED");
        assertThat(count(tenant, "commercial.package.expired")).isEqualTo(1);
        assertThat(count(tenant, "commercial.adjustment.revoked")).isZero();
    }

    /** 扫描时合格不能代替锁后资格：等待期间订阅重新受限，包必须继续PENDING。 */
    @Test
    void pendingActivationRechecksSubscriptionAfterWaitingForTenant() throws Exception {
        UUID tenant = tenant("待生效资格重检");
        jdbc.update("""
                UPDATE sys_tenant_subscription SET status = 'RESTRICTED_FREE', grace_ends_at = now(), restricted_at = now()
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenant);
        Purchased purchased = buy(tenant);
        assertThat(state(purchased.id())).isEqualTo("PENDING");
        jdbc.update("""
                UPDATE sys_tenant_subscription SET status = 'ACTIVE', grace_ends_at = NULL, restricted_at = NULL
                 WHERE tenant_id = ? AND status = 'RESTRICTED_FREE'
                """, tenant);
        var advancing = new AtomicReference<Future<Outcome<ResourcePackageAdvanceReport>>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.execute(status -> {
                int holder = lockTenant(tenant);
                advancing.set(executor.submit(() -> named("pending", () -> packages.advance(Instant.now()))));
                awaitBlockedBy("pending", holder);
                jdbc.update("""
                        UPDATE sys_tenant_subscription SET status = 'RESTRICTED_FREE', grace_ends_at = now(), restricted_at = now()
                         WHERE tenant_id = ? AND status = 'ACTIVE'
                        """, tenant);
                return null;
            });
            Outcome<ResourcePackageAdvanceReport> result = advancing.get().get(15, TimeUnit.SECONDS);
            assertThat(result.failure()).isNull();
            assertThat(result.value().activatedPending()).isZero();
            assertThat(result.value().tenantsInvalidated()).isZero();
        }
        assertThat(state(purchased.id())).isEqualTo("PENDING");
        assertThat(count(tenant, "commercial.package.activated")).isZero();
    }

    private void awaitBlockedBy(String name, int holder) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(pids).containsKey(name);
            // PG可能把后来的同一行等待者排在第一等待者的tuple锁后面，检查整条阻塞链。
            assertThat(jdbc.queryForObject("""
                    WITH RECURSIVE blockers(pid) AS (
                        SELECT unnest(pg_blocking_pids(?))
                        UNION
                        SELECT unnest(pg_blocking_pids(pid)) FROM blockers
                    )
                    SELECT EXISTS (SELECT 1 FROM blockers WHERE pid = ?)
                    """, Boolean.class, pids.get(name), holder))
                    .as("%s实际沿PG阻塞链等待本次持锁会话", name).isTrue();
        });
    }

    private int lockTenant(UUID tenant) {
        jdbc.execute("SET LOCAL lock_timeout = '8s'");
        jdbc.queryForObject("SELECT id FROM sys_tenant WHERE id = ? FOR UPDATE", UUID.class, tenant);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private <T> Outcome<T> named(String name, Callable<T> operation) {
        probe.set(name);
        try { return capture(operation); }
        finally { probe.remove(); TenantContext.clear(); }
    }

    private static <T> Outcome<T> capture(Callable<T> operation) {
        try { return new Outcome<>(operation.call(), null); }
        catch (Throwable failure) { return new Outcome<>(null, failure); }
    }

    private UUID tenant(String label) {
        UUID id = transactions.execute(status -> tenants.createTenant(label));
        ownedTenants.add(id);
        return id;
    }

    private Purchased buy(UUID tenant) {
        TenantOrder order = packages.createSimulatedPackageOrder(tenant, "DEVICES_MAX", 2, null);
        return new Purchased(order, packages.applySimulatedPackagePaymentSucceeded(order.id(), "pay-" + order.id()).packageId());
    }

    private void expireFixture(UUID id, Instant end) {
        assertThat(jdbc.update("UPDATE sys_tenant_resource_package SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.from(end.minus(Duration.ofDays(1))), Timestamp.from(end), id)).isEqualTo(1);
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT status FROM sys_tenant_resource_package WHERE id = ?", String.class, id);
    }

    private int count(UUID tenant, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE tenant_id = ? AND action = ?",
                Integer.class, tenant, action);
    }

    @AfterEach
    void cleanOwnedFacts() {
        TenantContext.clear();
        for (UUID tenant : ownedTenants) {
            jdbc.update("DELETE FROM sys_tenant_refund WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant_resource_package WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id = ?", tenant);
        }
    }

    private record Purchased(TenantOrder order, UUID id) {}
    private record Outcome<T>(T value, Throwable failure) {}
}
