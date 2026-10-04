package com.things.link.support.idempotency;

import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等存储的集成测试。
 *
 * <p>必须连真实 PostgreSQL：被验证的行为全部来自数据库本身 —— 唯一约束在并发下的
 * 仲裁、{@code NULLS NOT DISTINCT} 的语义、{@code IS NOT DISTINCT FROM} 的匹配。
 * 这些用内存替身或 mock 一个都测不出来。
 */
@DisplayName("幂等存储（PostgreSQL）")
class IdempotencyStoreTests extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        // 容器全 JVM 共享（AbstractIntegrationTest 的取舍），写数据的测试必须自己清理
        // 用 DELETE 而不是 TRUNCATE：TRUNCATE 是独立权限，应用角色不该拥有它
        // （能 TRUNCATE 就能绕过 RLS 清空整张表）
        jdbcTemplate.update("DELETE FROM sys_idempotency_record");
    }

    private IdempotencyRecord record(UUID tenantId, UUID projectId, String key, String bodyHash) {
        return new IdempotencyRecord(
                Uuid7.generate(), tenantId, projectId, key,
                "POST", "/api/v1/devices", bodyHash,
                IdempotencyRecord.Status.IN_PROGRESS,
                Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("首次抢占成功，同键再次抢占失败")
    void acquiresOnceOnly() {
        UUID tenantId = Uuid7.generate();

        assertThat(store.tryAcquire(record(tenantId, null, "key-1", "hash-a"))).isTrue();
        assertThat(store.tryAcquire(record(tenantId, null, "key-1", "hash-a"))).isFalse();
    }

    /**
     * 这条守的是迁移脚本里的 {@code NULLS NOT DISTINCT}。
     *
     * <p>PostgreSQL 默认把 NULL 视为互不相等，没有这个选项的话，S1 接入认证之前
     * （tenant_id / project_id 全是 NULL）幂等会<b>完全失效且不报任何错</b>。
     */
    @Test
    @DisplayName("租户为 NULL 时幂等仍然生效（NULLS NOT DISTINCT）")
    void enforcesUniquenessWhenTenantIsNull() {
        assertThat(store.tryAcquire(record(null, null, "key-null", "hash-a"))).isTrue();
        assertThat(store.tryAcquire(record(null, null, "key-null", "hash-a")))
                .as("tenant_id 为 NULL 时唯一约束必须仍然生效")
                .isFalse();
    }

    /**
     * 对应 JdbcIdempotencyStore 里用 {@code IS NOT DISTINCT FROM} 而非 {@code =} 的原因：
     * SQL 中 {@code = NULL} 永远不成立，写成 {@code = ?} 的话未认证请求永远查不到记录。
     */
    @Test
    @DisplayName("租户为 NULL 时也能按键查到记录")
    void findsRecordWhenTenantIsNull() {
        store.tryAcquire(record(null, null, "key-null", "hash-a"));

        assertThat(store.find(null, null, "key-null", "POST", "/api/v1/devices"))
                .as("tenant_id 为 NULL 时必须能查到，否则重复请求会被当成首次执行")
                .isPresent();
    }

    @Test
    @DisplayName("不同租户的相同幂等键互不影响")
    void isolatesAcrossTenants() {
        assertThat(store.tryAcquire(record(Uuid7.generate(), null, "same-key", "hash-a"))).isTrue();
        assertThat(store.tryAcquire(record(Uuid7.generate(), null, "same-key", "hash-a"))).isTrue();
    }

    @Test
    @DisplayName("同一幂等键用在不同接口上互不影响")
    void isolatesAcrossEndpoints() {
        UUID tenantId = Uuid7.generate();
        IdempotencyRecord onDevices = record(tenantId, null, "same-key", "hash-a");
        IdempotencyRecord onCommands = new IdempotencyRecord(
                Uuid7.generate(), tenantId, null, "same-key",
                "POST", "/api/v1/commands", "hash-a",
                IdempotencyRecord.Status.IN_PROGRESS,
                Instant.now().plus(Duration.ofHours(24)));

        assertThat(store.tryAcquire(onDevices)).isTrue();
        assertThat(store.tryAcquire(onCommands))
                .as("「创建设备」与「下发命令」用同一个 key 是两回事，不能互相顶掉")
                .isTrue();
    }

    @Test
    @DisplayName("complete 之后只保留完成墓碑")
    void storesCompletionTombstone() {
        IdempotencyRecord acquired = record(null, null, "key-complete", "hash-a");
        store.tryAcquire(acquired);

        store.complete(acquired.id());

        IdempotencyRecord found = store.find(null, null, "key-complete", "POST", "/api/v1/devices").orElseThrow();
        assertThat(found.status()).isEqualTo(IdempotencyRecord.Status.COMPLETED);
        Integer responseColumns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_idempotency_record'
                   AND column_name IN ('response_status', 'response_body')
                """, Integer.class);
        assertThat(responseColumns).as("Schema必须阻止公共层重新存入授权或凭据响应").isZero();
    }

    /**
     * 业务失败后必须能重试。不释放的话记录卡在 IN_PROGRESS，
     * 客户端后续重试全部收到 409，直到 24 小时后过期 —— 一次偶发故障变成一整天不可用。
     */
    @Test
    @DisplayName("release 之后同一幂等键可以重新抢占")
    void allowsRetryAfterRelease() {
        IdempotencyRecord first = record(null, null, "key-retry", "hash-a");
        store.tryAcquire(first);

        store.release(first.id());

        assertThat(store.tryAcquire(record(null, null, "key-retry", "hash-a")))
                .as("业务失败释放后，客户端必须能用同一个 key 重试")
                .isTrue();
    }

    /** 清理器只删除过期记录并服从单批上限，仍在客户端重试窗口内的事实必须保留。 */
    @Test
    void deletesExpiredRecordsInBoundedBatches() {
        Instant now = Instant.now();
        IdempotencyRecord expiredA = record(null, null, "expired-a", "hash-a");
        IdempotencyRecord expiredB = record(null, null, "expired-b", "hash-a");
        IdempotencyRecord active = record(null, null, "active", "hash-a");
        store.tryAcquire(withExpiry(expiredA, now.minusSeconds(2)));
        store.tryAcquire(withExpiry(expiredB, now.minusSeconds(1)));
        store.tryAcquire(withExpiry(active, now.plusSeconds(60)));

        assertThat(store.deleteExpired(1)).isEqualTo(1);
        assertThat(store.deleteExpired(1)).isEqualTo(1);
        assertThat(store.deleteExpired(1)).isZero();
        assertThat(store.find(null, null, "active", "POST", "/api/v1/devices")).isPresent();
    }

    /** 复制测试记录并仅替换到期时刻，保持构造意图可读。 */
    private static IdempotencyRecord withExpiry(IdempotencyRecord source, Instant expiresAt) {
        return new IdempotencyRecord(
                source.id(), source.tenantId(), source.projectId(), source.idempotencyKey(),
                source.requestMethod(), source.requestPath(), source.requestBodyHash(), source.status(),
                expiresAt);
    }

    /**
     * 幂等机制的正确性完全依赖「抢占是原子的」。
     *
     * <p>如果实现写成「先查后插」，并发下会有多个线程都查到不存在、然后都执行业务 ——
     * 对物联网平台这意味着同一条下发命令被执行多次。这个测试就是为了钉死这一点。
     */
    @Test
    @DisplayName("并发抢占同一幂等键，有且只有一个成功")
    void exactlyOneWinsUnderConcurrency() throws Exception {
        int threads = 16;
        UUID tenantId = Uuid7.generate();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<Boolean>) () ->
                            store.tryAcquire(record(tenantId, null, "race-key", "hash-a")))
                    .toList();

            long winners = executor.invokeAll(tasks).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .count();

            assertThat(winners)
                    .as("%d 个并发请求中必须恰好一个抢到执行权", threads)
                    .isEqualTo(1);
        }
    }

}
