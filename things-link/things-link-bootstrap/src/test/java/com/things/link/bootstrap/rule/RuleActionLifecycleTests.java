package com.things.link.bootstrap.rule;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.rule.application.outbox.RuleSideEffectOutboxBridge;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.PublishedRulePlan;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionException;
import com.things.link.rule.application.queue.RuleExecutionFailure;
import com.things.link.rule.application.queue.RuleExecutionKey;
import com.things.link.rule.application.queue.RuleExecutionReceiptClaim;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * S12-P0-5e3消息规则新动作准入：真实桥接事务同时写动作Outbox与完成回执。
 * 本类从已执行成功的合法动作意图开始，不用脚本worker替身，也不宣称验收外部通知送达。
 * 独占PG承载真实版本/计划/receipt及APP RLS；原桥接代理自己开启事务。
 */
@Import(RuleActionLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"LIFECYCLE_POSTGRES"})
class RuleActionLifecycleTests extends AbstractIntegrationTest {
    /** 物理专库隔离全局后台worker和历史规则事实。 */
    private static final String DATABASE_NAME = "rule_action_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 使用项目约定真实TimescaleDB镜像及APP/迁移owner。 */
    private static final PostgreSQLContainer<?> LIFECYCLE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Registrar读取前启动专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 对全部关键表观察完整物理行，避免RLS空投影与仅计数的假通过。 */
    private static final List<String> FACT_TABLES = List.of("rule_message", "rule_version", "rule_execution_receipt",
            "rule_execution_log", "sys_outbox_event", "rule_notification_delivery", "rule_device_action_delivery");
    /** 父runner走共享PG，必须真正替换该独立路径。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuotaRelaxation;
    /** 本例不运行自动任务调度。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 本例只验证可靠意图，禁止自动通知投递消耗Outbox。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 无关回补不参与本事务证据。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 无关命令worker不能污染规则动作事实。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandTimeoutScanner;
    /** 原事务桥接服务，spy只观察实际APP事务。 */
    @MockitoSpyBean private RuleSideEffectOutboxBridge bridge;
    /** 真实受限计划目录，不能手造不存在的rule/version。 */
    @Autowired private PublishedRuleCatalog catalog;
    /** 真实回执抢占与完成，初始租约必须独立提交。 */
    @MockitoSpyBean private RuleExecutionReceiptStore receipts;
    /** 真实项目许可，spy只在原方法真正持锁后设置观察屏障。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 删除必须使用原OWNER生命周期服务。 */
    @Autowired private ProjectService projects;
    /** 原事务连接身份与专库事实。 */
    @Autowired private JdbcTemplate jdbc;
    /** Flyway落点验证。 */
    @Autowired private Environment environment;
    /** 原生产JSON mapper。 */
    @Autowired private ObjectMapper mapper;
    /** 每例独立项目、版本和已确权设备。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 原桥接非只读物理事务调用次数。 */
    private final AtomicInteger bridgeCalls = new AtomicInteger();
    /** 原桥接PID与许可调用的物理事务身份。 */
    private final AtomicInteger bridgePid = new AtomicInteger();
    /** 只有在原事务真正开始后才交出PID给主线程观察。 */
    private final CompletableFuture<Integer> entered = new CompletableFuture<>();
    /** 许可后屏障仅用于持锁先得的用例，其余路径不增加等待。 */
    private Runnable afterPermit = () -> { };
    /** 由真实活动版本投影生成的信封，引用真实已抢占回执。 */
    private RuleExecutionEnvelope envelope;

    /** 合法版本/设备就绪后，真实目录冻结计划并真实抢占回执，最后装配只观察原代理的spy。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        PublishedRulePlan plan = inScope(() -> catalog.resolve(fixture.tenantId(), fixture.projectId(), fixture.messageId()));
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().getFirst().ruleId()).isEqualTo(fixture.ruleId());
        assertThat(plan.steps().getFirst().ruleVersionId()).isEqualTo(fixture.versionId());
        assertThat(plan.steps().getFirst().createdBy()).isEqualTo(fixture.accountId());
        RuleMessage message = new RuleMessage(fixture.messageId(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                "rule-action-lifecycle", Instant.now(), "PROPERTY", mapper.createObjectNode(), Map.of());
        envelope = new RuleExecutionEnvelope(new RuleExecutionKey(fixture.projectId(), fixture.messageId(),
                fixture.ruleId(), fixture.versionId()), fixture.tenantId(), message, plan, 1, Instant.now());
        assertThat(inScope(() -> receipts.tryClaim(envelope))).isEqualTo(RuleExecutionReceiptClaim.ACQUIRED);
        JsonNode receipt = receipt(facts());
        assertThat(receipt.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(receipt.path("lease_until").isNull()).isFalse();
        assertThat(AopUtils.isAopProxy(bridge)).isTrue();
        RuleSideEffectOutboxBridge target = AopTestUtils.getUltimateTargetObject(bridge);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            assertAppTransaction();
            bridgePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            entered.complete(bridgePid.get());
            bridgeCalls.incrementAndGet();
            try { return invocation.callRealMethod(); }
            finally { bridgePid.set(0); }
        }).when(target).completeWithSideEffects(any(), any());
        ProjectLifecycleAccessService permit = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            assertAppTransaction();
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(bridgePid.get());
            boolean allowed = (boolean) invocation.callRealMethod();
            if (allowed) afterPermit.run();
            return allowed;
        }).when(permit).lockActiveForWrite(any(), any());
    }

    /** 归档先提交后不能产生新通知Outbox或把回执伪完成；旧生产会新增1行并COMPLETED形成RED。 */
    @Test
    void archivedProjectRejectsNotificationAndKeepsReceiptUnfinished() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        Map<String, List<String>> before = facts();
        Throwable failure = catchThrowable(this::complete);
        Map<String, List<String>> after = facts();
        assertThat(bridgeCalls).hasValue(1);
        assertThat(after.get("sys_outbox_event")).as("项目已归档时不能新增通知动作Outbox").isEqualTo(before.get("sys_outbox_event"));
        assertThat(after).as("拒绝新动作时原回执和全部动作事实整体不变").isEqualTo(before);
        assertThat(failure).isInstanceOf(RuleExecutionException.class);
        assertThat(((RuleExecutionException) failure).failure()).isEqualTo(RuleExecutionFailure.SECURITY_REJECTED);
    }

    /** ACTIVE对照必须真实写Outbox并完成已有receipt；后续真实claim吸收完成重投。 */
    @Test
    void activeNotificationAndReceiptCommitTogether() throws Exception {
        complete();
        Map<String, List<String>> after = facts();
        assertThat(after.get("sys_outbox_event")).hasSize(1);
        JsonNode outbox = mapper.readTree(after.get("sys_outbox_event").getFirst());
        assertThat(outbox.path("event_type").asText()).isEqualTo("RULE_NOTIFICATION_DELIVERY_REQUEST");
        assertThat(outbox.path("tenant_id").asText()).isEqualTo(fixture.tenantId().toString());
        assertThat(outbox.path("project_id").asText()).isEqualTo(fixture.projectId().toString());
        assertThat(outbox.path("status").asText()).isEqualTo("PENDING");
        assertThat(receipt(after).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(receipt(after).path("completed_at").isNull()).isFalse();
        assertThat(receipt(after).path("lease_until").isNull()).isTrue();
        assertThat(inScope(() -> receipts.tryClaim(envelope))).isEqualTo(RuleExecutionReceiptClaim.COMPLETED);
        assertThat(facts()).isEqualTo(after);
    }

    /** 真实OWNER删除只保留历史行，不能继续发送规则通知或完成动作receipt。 */
    @Test
    void deletedProjectRejectsNotificationWithoutChangingReceipt() throws Exception {
        inScope(() -> { projects.delete(fixture.projectId()); return null; });
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5); query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); }
        }
        Map<String, List<String>> before = facts();
        assertSecurityRejected(catchThrowable(this::complete));
        assertThat(facts()).isEqualTo(before);
    }

    /** 空动作仍是桥接成功收口，不能绕过项目许可把冻结receipt伪完成。 */
    @Test
    void archivedProjectRejectsEvenEmptyIntents() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        Map<String, List<String>> before = facts();
        assertSecurityRejected(catchThrowable(() -> complete(envelope, List.of())));
        assertThat(facts()).isEqualTo(before);
    }

    /** 信封内部tenant与message一致仍不足以授权其他租户项目，必须以项目持久归属判定。 */
    @Test
    void consistentlyForgedTenantCannotPublishForAnotherProject() throws Exception {
        UUID otherTenant = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '规则动作邻租户')", otherTenant);
        }
        RuleMessage old = envelope.message();
        RuleMessage wrongMessage = new RuleMessage(old.messageId(), otherTenant, old.projectId(), old.deviceId(),
                old.traceId(), old.occurredAt(), old.type(), old.payload(), old.metadata());
        RuleExecutionEnvelope wrong = new RuleExecutionEnvelope(envelope.key(), otherTenant, wrongMessage,
                envelope.plan(), envelope.attempt(), envelope.enqueuedAt());
        Map<String, List<String>> before = facts();
        assertSecurityRejected(catchThrowable(() -> complete(wrong, intents())));
        assertThat(facts()).isEqualTo(before);
        complete();
        assertThat(receipt(facts()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(facts().get("sys_outbox_event")).hasSize(1);
    }

    /** 项目SHARE先得到：归档真实等待直到动作Outbox与receipt原事务一起提交。 */
    @Test
    void acquiredPermissionBlocksArchiveThroughSideEffectAndReceiptCommit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> permitted = new CompletableFuture<>();
        CompletableFuture<Integer> freezerPid = new CompletableFuture<>();
        afterPermit = () -> { permitted.complete(bridgePid.get()); awaitLatch(release); };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Throwable> completed = workers.submit(() -> catchThrowable(this::complete));
        try {
            int writerPid = permitted.get(10, TimeUnit.SECONDS);
            Future<?> frozen = workers.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    owner.setAutoCommit(false);
                    freezerPid.complete(ownerPid(owner));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                    owner.commit();
                    return null;
                }
            });
            int waiter = freezerPid.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertBlockedBy(waiter, writerPid));
            assertThat(facts().get("sys_outbox_event")).isEmpty();
            assertThat(receipt(facts()).path("status").asText()).isEqualTo("IN_PROGRESS");
            release.countDown();
            assertThat(completed.get(10, TimeUnit.SECONDS)).isNull();
            frozen.get(10, TimeUnit.SECONDS);
            assertThat(facts().get("sys_outbox_event")).hasSize(1);
            assertThat(receipt(facts()).path("status").asText()).isEqualTo("COMPLETED");
        } finally {
            release.countDown();
            finishWorkers(workers);
        }
    }

    /** 归档锁先得到：原桥接等待后必须重读已提交状态，不能沿等待前ACTIVE快照写动作。 */
    @Test
    void archiveLockFirstRejectsAfterWaitingForCommittedState() throws Exception {
        Map<String, List<String>> before = facts();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            int lockerPid = ownerPid(owner);
            Future<Throwable> completion = worker.submit(() -> catchThrowable(this::complete));
            int writerPid = entered.get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertBlockedBy(writerPid, lockerPid));
            owner.commit();
            assertSecurityRejected(completion.get(10, TimeUnit.SECONDS));
            assertThat(facts()).isEqualTo(before);
        } finally { finishWorkers(worker); }
    }

    /** 原五秒SQL预算超时不是SECURITY_REJECTED；所有事实回滚，释放阻塞后同一工作可重新完成。 */
    @Test
    void originalSqlTimeoutRollsBackActionsAndReceiptAndAllowsRetry() throws Exception {
        Map<String, List<String>> before = facts();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE sys_project SET status=status WHERE id=?", fixture.projectId());
            int lockerPid = ownerPid(owner);
            Future<Throwable> completion = worker.submit(() -> catchThrowable(this::complete));
            int writerPid = entered.get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertBlockedBy(writerPid, lockerPid));
            Throwable failure = completion.get(10, TimeUnit.SECONDS);
            assertThat(sqlState(failure)).isEqualTo("57014");
            assertThat(failure).isNotInstanceOf(RuleExecutionException.class);
            assertThat(facts()).isEqualTo(before);
            owner.rollback();
        } finally { finishWorkers(worker); }
        complete();
        assertThat(facts().get("sys_outbox_event")).hasSize(1);
        assertThat(receipt(facts()).path("status").asText()).isEqualTo("COMPLETED");
    }

    /** 真实Outbox与真实receipt均已写入后抛失败，必须同事务回滚；恢复不能留下重复意图。 */
    @Test
    void receiptFailureAfterRealWritesRollsBackOutboxAndCanRecover() throws Exception {
        Map<String, List<String>> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicInteger actualCompletedWrites = new AtomicInteger();
        RuleExecutionReceiptStore receiptTarget = AopTestUtils.getUltimateTargetObject(receipts);
        doAnswer(invocation -> {
            assertAppTransaction();
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(bridgePid.get());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?", Integer.class,
                    fixture.projectId())).isEqualTo(1);
            Object result = invocation.callRealMethod();
            assertThat(jdbc.queryForObject("SELECT status FROM rule_execution_receipt WHERE project_id=? AND message_id=?",
                    String.class, fixture.projectId(), fixture.messageId())).isEqualTo("COMPLETED");
            actualCompletedWrites.incrementAndGet();
            if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("验收：回执完成实际写后失败");
            return result;
        }).when(receiptTarget).complete(any());
        assertThat(catchThrowable(this::complete))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessage("验收：回执完成实际写后失败");
        assertThat(actualCompletedWrites).hasValue(1);
        assertThat(facts()).isEqualTo(before);
        complete();
        assertThat(actualCompletedWrites).hasValue(2);
        assertThat(facts().get("sys_outbox_event")).hasSize(1);
        assertThat(receipt(facts()).path("status").asText()).isEqualTo("COMPLETED");
    }

    /** 确定冻结必须落既有不可重试分类，不能接受任意异常冒充安全拒绝。 */
    private void assertSecurityRejected(Throwable failure) {
        assertThat(failure).isInstanceOf(RuleExecutionException.class);
        assertThat(((RuleExecutionException) failure).failure()).isEqualTo(RuleExecutionFailure.SECURITY_REJECTED);
    }

    /** PostgreSQL真实阻塞边证明行锁依赖，而不是通过睡眠或Future未完成猜测。 */
    private void assertBlockedBy(int waiterPid, int blockerPid) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT ?=ANY(pg_blocking_pids(?))")) {
            query.setQueryTimeout(5); query.setInt(1, blockerPid); query.setInt(2, waiterPid);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); }
        }
    }

    /** 独立owner PID仅用于观测锁，不以owner运行生产业务。 */
    private int ownerPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getInt(1); }
        }
    }

    /** SQLSTATE从原异常链读取，避免将项目拒绝或模拟异常叫作真实数据库超时。 */
    private String sqlState(Throwable failure) {
        for (Throwable item = failure; item != null; item = item.getCause())
            if (item instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 测试屏障有界，finally始终释放，不修改生产JDBC预算。 */
    private void awaitLatch(CountDownLatch release) {
        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("规则验收屏障中断", failure); }
    }

    /** 每例释放worker并等待线程结束，防止残余连接锁影响下一项。 */
    private void finishWorkers(ExecutorService worker) throws InterruptedException {
        worker.shutdown();
        if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 桥接从已验证意图进入；不使用TransactionTemplate外包其原代理事务。 */
    private void complete() { complete(envelope, intents()); }

    /** 同一已发布通知配置生成合法host意图，原dispatcher负责真实动作落库。 */
    private List<RuleSideEffectIntent> intents() {
        return List.of(new RuleSideEffectIntent("notification", notificationConfig()));
    }

    /** 信封与线程可信tenant保持一致，错配验收只改变owner归属，不制造构造器内部矛盾。 */
    private void complete(RuleExecutionEnvelope work, List<RuleSideEffectIntent> intents) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        TenantContext.set(new TenantScope(work.tenantId(), work.key().projectId(), fixture.accountId()));
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            bridge.completeWithSideEffects(work, intents);
        } finally { TenantContext.clear(); }
    }

    /** 与已发布版本相同的合法通知动作配置，且不依赖任何公网供应商。 */
    private JsonNode notificationConfig() {
        return mapper.createObjectNode().put("channel", "email").put("recipient", "ops@example.com")
                .put("subject", "规则动作验收").put("body", "冻结后不得产生新通知意图");
    }

    /** 每例只有一个真实已抢占receipt；不能用数量或默认构造代替可信身份。 */
    private JsonNode receipt(Map<String, List<String>> facts) {
        assertThat(facts.get("rule_execution_receipt")).hasSize(1);
        return mapper.readTree(facts.get("rule_execution_receipt").getFirst());
    }

    /** 与RuleExecutionCoordinator相同的可信Worker范围，选择DATA池但不创建外层事务。 */
    private <T> T inScope(Supplier<T> work) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return work.get();
        } finally { TenantContext.clear(); }
    }

    /** 原事务实际使用专库APP且RLS项目轴正确，不能以owner连接绕过。 */
    private void assertAppTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_setting('app.project_id')", String.class)).isEqualTo(fixture.projectId().toString());
    }

    /** 已知合法源码和动作存成不可变版本，真实目录负责投影；本例不重复验收控制台脚本编译。 */
    private void seed() throws Exception {
        String source = "input => input";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        String actions = mapper.writeValueAsString(List.of(Map.of("nodeType", "notification-action", "config", notificationConfig())));
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '规则动作验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '规则动作OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '规则动作验收项目', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "rule_action_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'rule_action_type', '规则动作类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'rule_action_device', '规则动作设备', 'ONLINE')",
                    fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO rule_message(id,tenant_id,project_id,name,status,created_by,created_at,updated_at) VALUES (?, ?, ?, '规则动作验收', 'DRAFT', ?, ?, ?)",
                    fixture.ruleId(), fixture.tenantId(), fixture.projectId(), fixture.accountId(), now, now);
            execute(owner, "INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,created_by,created_at,actions) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?::jsonb)",
                    fixture.versionId(), fixture.tenantId(), fixture.projectId(), fixture.ruleId(), source, digest, fixture.accountId(), now, actions);
            execute(owner, "UPDATE rule_message SET status='ACTIVE',active_version_id=? WHERE id=?", fixture.versionId(), fixture.ruleId());
            owner.commit();
        }
    }

    /** 专库独立owner读取七张物理表的完整行，避免RLS空投影把有写误判为零。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, fixture.projectId());
                    List<String> values = new ArrayList<>();
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                    result.put(table, List.copyOf(values));
                }
            }
        }
        return result;
    }

    /** 同库同角色逐连接核对；同时确证task事实对象没有变成owner也受WHERE过滤的视图。 */
    private void verifyIsolation() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (Object disabled : List.of(unusedRestQuotaRelaxation, unusedTaskScanner, unusedNotificationCoordinator, unusedBackfillScanner, unusedCommandTimeoutScanner))
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
             Connection owner = fixtureOwnerConnection()) {
            verifyOwnerIdentity(migration);
            verifyOwnerIdentity(owner);
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                    query.setQueryTimeout(5);
                    query.setString(1, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isIn("r", "p");
                    }
                }
            }
        }
    }

    /** 明确验证Flyway/独立观察均使用专库owner，不能只有相同URL文本。 */
    private void verifyOwnerIdentity(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(LIFECYCLE_POSTGRES.getUsername());
            }
        }
    }


    /** 不删除不可变规则版本或审计，专库由Testcontainers回收；只清当前线程可信范围。 */
    @AfterEach
    void clearScope() { TenantContext.clear(); }

    /** 所有独立owner路径落专库，不能继承默认共享数据库连接。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, LIFECYCLE_POSTGRES.getUsername(), LIFECYCLE_POSTGRES.getPassword());
    }

    /** 固定SQL仅接受参数化身份；5秒界限防止未来锁变化令测试无期限卡住。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 容器生命周期由本类OwnedTestContainers负责；不关闭其他上下文的共享容器。 */
    private static String startDatabase() { LIFECYCLE_POSTGRES.start(); return LIFECYCLE_POSTGRES.getJdbcUrl(); }

    /** 专库继承原角色、凭证及池预算，仅覆盖地址并禁止本片不验证的自动副作用。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Registrar优先于父DynamicPropertySource；无业务Bean或额度成功替身。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }



    /** 全部父子行均真实落专库，规则、版本、消息与设备身份独立。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId,
                           UUID deviceId, UUID ruleId, UUID versionId, UUID messageId) { }
}
