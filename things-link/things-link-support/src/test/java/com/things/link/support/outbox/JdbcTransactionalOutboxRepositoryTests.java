package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 验证 Outbox 的 RLS 写入与 SECURITY DEFINER 领取边界。 */
class JdbcTransactionalOutboxRepositoryTests extends AbstractIntegrationTest {

    /** 被测 Outbox 公开端口。 */
    @Autowired private TransactionalOutboxRepository repository;
    /** ADR0071等价历史信封查询端口。 */
    @Autowired private TransactionalOutboxReader reader;
    /** 直接 SQL 只用于观察 RLS 是否仍生效。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 建立调用 append 所需的真实业务事务。 */
    @Autowired private TransactionTemplate transactionTemplate;

    /** 清理测试线程身份，避免数据源借出的下一条连接继承项目范围。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * 普通查询在无项目上下文时必须 fail-closed，而受限函数仍可供后台领取后确认。
     */
    @Test
    void claimsAcrossProjectsWithoutGrantingDirectCrossProjectRead() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        OutboxEvent event = event(tenantId, projectId);

        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        transactionTemplate.executeWithoutResult(status -> repository.append(event));
        TenantContext.clear();

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM sys_outbox_event WHERE id = ?", UUID.class, event.id()))
                .as("应用角色未设项目上下文时仍不得直接读取 Outbox")
                .isEmpty();

        OutboxClaim claim = claim(event.id());
        assertThat(repository.markPublished(event.id(), claim.leaseToken())).isTrue();

        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM sys_outbox_event WHERE id = ?", Boolean.class, event.id()))
                .isTrue();
    }

    /** 错误租约不得确认已经由其他发布器领取的事件。 */
    @Test
    void rejectsMarkingWithDifferentLeaseToken() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        OutboxEvent event = event(tenantId, projectId);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        transactionTemplate.executeWithoutResult(status -> repository.append(event));
        TenantContext.clear();

        OutboxClaim claim = claim(event.id());

        assertThat(repository.markPublished(event.id(), UUID.randomUUID()))
                .as("旧实例只知道事件 ID 时不能覆盖当前租约的投递状态")
                .isFalse();
        assertThat(repository.markRetry(event.id(), claim.leaseToken(), Instant.now().plusSeconds(1), "test"))
                .isTrue();
    }

    /** 等价配置查询受RLS约束并可使用现有项目聚合索引，无需为JSON载荷另建全局索引。 */
    @Test
    void findsEquivalentJsonWithProjectAggregateIndex() throws java.sql.SQLException {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID gatewayId = Uuid7.generate();
        OutboxEvent event = new OutboxEvent(Uuid7.generate(), tenantId, projectId, "DEVICE_CONFIG", gatewayId,
                "DEVICE_CONFIG_PUSH", gatewayId.toString(), "{\"version\":3,\"points\":[]}",
                "0123456789abcdef0123456789abcdef", Instant.now());
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        transactionTemplate.executeWithoutResult(status -> repository.append(event));

        // ADR0090增加项目清理索引后，单行表上的最便宜索引不唯一；用同项目不同聚合证明聚合选择性。
        // 噪声已发布，避免进入全局publisher领取队列；仅当前测试项目，finally回收不污染后续场景。
        try (var fixture = fixtureOwnerConnection()) {
            try (var seed = fixture.prepareStatement("""
                    INSERT INTO public.sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id,status,published_at)
                    SELECT gen_random_uuid(),?,?,'DEVICE_CONFIG',gen_random_uuid(),'DEVICE_CONFIG_PUSH','tc.device.config',gen_random_uuid()::text,'{}','test-index','PUBLISHED',now() FROM generate_series(1,2000)
                    """)) {
                seed.setObject(1, tenantId); seed.setObject(2, projectId); seed.executeUpdate();
            }
            try {
                try (var statistics = fixture.createStatement()) { statistics.execute("ANALYZE public.sys_outbox_event"); }
                transactionTemplate.executeWithoutResult(status -> {
                    assertThat(reader.existsEquivalent(tenantId, projectId, "DEVICE_CONFIG", gatewayId,
                            "DEVICE_CONFIG_PUSH", "tc.device.config", gatewayId.toString(),
                            "{\"points\":[],\"version\":3}"))
                            .as("JSON对象字段顺序不同仍应匹配同一完整配置")
                            .isTrue();
                    assertThat(reader.existsEquivalent(tenantId, projectId, "DEVICE_CONFIG", gatewayId,
                            "DEVICE_CONFIG_PUSH", "tc.device.config", gatewayId.toString(),
                            "{\"points\":[],\"version\":4}"))
                            .isFalse();
                    jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
                    List<String> plan = jdbcTemplate.queryForList("""
                            EXPLAIN SELECT 1 FROM sys_outbox_event
                             WHERE tenant_id=? AND project_id=? AND aggregate_type=? AND aggregate_id=?
                               AND event_type=? AND destination_topic=? AND partition_key=?
                               AND payload::jsonb=?::jsonb
                            """, String.class, tenantId, projectId, "DEVICE_CONFIG", gatewayId,
                            "DEVICE_CONFIG_PUSH", "tc.device.config", gatewayId.toString(), event.payload());
                    assertThat(String.join("\n", plan)).contains("sys_outbox_event_project_aggregate_idx");
                });

                TenantContext.set(new TenantScope(Uuid7.generate(), Uuid7.generate(), Uuid7.generate()));
                transactionTemplate.executeWithoutResult(status -> assertThat(reader.existsEquivalent(
                        tenantId, projectId, "DEVICE_CONFIG", gatewayId, "DEVICE_CONFIG_PUSH",
                        "tc.device.config", gatewayId.toString(), event.payload())).isFalse());
            } finally {
                try (var cleanup = fixture.prepareStatement("DELETE FROM public.sys_outbox_event WHERE project_id=? AND id<>?")) {
                    cleanup.setObject(1, projectId); cleanup.setObject(2, event.id()); cleanup.executeUpdate();
                }
            }
        }
    }

    /**
     * 同一 Topic+key 的后继必须等待 head 完成；相同 key 位于不同 Topic 时不得被错误串行化。
     */
    @Test
    void arbitratesHeadOfLinePerDestinationTopicAndPartitionKey() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        String sharedKey = Uuid7.generate().toString();
        OutboxEvent laneHead = event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                sharedKey,
                Instant.parse("2026-08-21T00:00:00Z"));
        OutboxEvent laneFollower = event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                sharedKey,
                Instant.parse("2026-08-21T00:00:01Z"));
        OutboxEvent otherTopic = event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_TERMINAL_EVENT,
                sharedKey,
                Instant.parse("2026-08-21T00:00:02Z"));

        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        transactionTemplate.executeWithoutResult(status -> {
            repository.append(laneHead);
            repository.append(laneFollower);
            repository.append(otherTopic);
        });
        TenantContext.clear();

        OutboxClaim firstClaim = claim(laneHead.id());
        assertThat(firstClaim.events()).extracting(OutboxEvent::id)
                .contains(laneHead.id(), otherTopic.id())
                .doesNotContain(laneFollower.id());
        assertThat(repository.markPublished(laneHead.id(), firstClaim.leaseToken())).isTrue();
        assertThat(repository.markPublished(otherTopic.id(), firstClaim.leaseToken())).isTrue();

        OutboxClaim secondClaim = claim(laneFollower.id());
        assertThat(secondClaim.events()).extracting(OutboxEvent::id).contains(laneFollower.id());
    }

    /**
     * 重叠领取事务跳过已锁定的 lane head 时，绝不能把同 lane follower 当作新的 head。
     *
     * <p>这是 G1-C4b-F16 的停库恢复反例：旧实例可能正持有恢复后的 head 行锁，新实例必须跳过整个 lane；
     * 否则 follower 会先得到 Kafka ACK 和 {@code published_at}，即使最终两行都发布也已经破坏分区内业务顺序。</p>
     *
     * @throws Exception 并发领取未在受控期限内完成时让测试明确失败
     */
    @Test
    void neverPromotesFollowerWhenAnotherClaimerLocksLaneHead() throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        String sharedKey = Uuid7.generate().toString();
        OutboxEvent laneHead = event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                sharedKey,
                Instant.EPOCH);
        OutboxEvent laneFollower = event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                sharedKey,
                Instant.EPOCH.plusSeconds(1));

        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        transactionTemplate.executeWithoutResult(status -> {
            repository.append(laneHead);
            repository.append(laneFollower);
        });
        TenantContext.clear();

        CountDownLatch headLocked = new CountDownLatch(1);
        CountDownLatch releaseHead = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<UUID>> firstClaim = executor.submit(() -> transactionTemplate.execute(status -> {
                List<UUID> ids = claimIds(UUID.randomUUID());
                headLocked.countDown();
                awaitLatch(releaseHead, "首个领取事务未获准结束");
                return ids;
            }));

            assertThat(headLocked.await(10, TimeUnit.SECONDS))
                    .as("首个领取事务必须已更新并锁住 lane head")
                    .isTrue();
            List<UUID> overlappingClaim = claimIds(UUID.randomUUID());
            assertThat(overlappingClaim)
                    .as("SKIP LOCKED 只能跳过忙碌 lane，不能提升它的 follower")
                    .doesNotContain(laneFollower.id());

            releaseHead.countDown();
            assertThat(firstClaim.get(10, TimeUnit.SECONDS)).contains(laneHead.id());
        } finally {
            releaseHead.countDown();
        }
    }

    /** 创建最小合法事件；该载荷在本测试只验证存取，消息契约由发布器单元测试验证。 */
    private static OutboxEvent event(UUID tenantId, UUID projectId) {
        return event(
                tenantId,
                projectId,
                KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                Uuid7.generate().toString(),
                Instant.EPOCH);
    }

    /** @return 可指定 lane 与创建顺序的最小合法事件 */
    private static OutboxEvent event(
            UUID tenantId,
            UUID projectId,
            String eventType,
            String partitionKey,
            Instant createdAt) {
        return new OutboxEvent(
                Uuid7.generate(),
                tenantId,
                projectId,
                "TEST",
                Uuid7.generate(),
                eventType,
                partitionKey,
                "{\"eventId\":\"test\"}",
                "0123456789abcdef0123456789abcdef",
                createdAt);
    }

    /**
     * 从复用容器遗留的待发布事件之后领取本测试事件。
     *
     * <p>测试配置会关闭后台发布器，其他 API 集成测试因此可能留下超过一批的合法事件。逐批领取既不
     * 绕过 RLS，也保留生产协议的 8 条上限；若十批后仍未出现，则说明环境存在异常积压并明确失败。</p>
     */
    private OutboxClaim claim(UUID eventId) {
        for (int batch = 0; batch < 10; batch++) {
            OutboxClaim claim = repository.claimReady(8, Duration.ofSeconds(30));
            if (claim.events().stream().anyMatch(event -> event.id().equals(eventId))) {
                return claim;
            }
        }
        throw new AssertionError("十批之内未领取到本测试创建的 Outbox 事件: " + eventId);
    }

    /**
     * 在当前事务内直接调用受限领取函数，以便测试在提交前保留真实 PostgreSQL 行锁。
     *
     * @param leaseToken 当前领取者的唯一租约令牌
     * @return 本轮实际领取的事件 ID
     */
    private List<UUID> claimIds(UUID leaseToken) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM claim_sys_outbox_events(?, ?, ?)",
                UUID.class,
                leaseToken,
                8,
                30);
    }

    /**
     * 把并发屏障超时转换成带上下文的失败，而不是让用例永久挂起。
     *
     * @param latch 待释放屏障
     * @param message 超时时的诊断
     */
    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待 Outbox 并发屏障时线程被中断", exception);
        }
    }
}
