package com.things.link.bootstrap.rule;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.CreateSceneCommand;
import com.things.link.rule.application.ExecuteSceneCommand;
import com.things.link.rule.application.RuleSceneExecutionView;
import com.things.link.rule.application.RuleSceneService;
import com.things.link.rule.application.RuleSceneView;
import com.things.link.shared.error.BusinessException;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** ADR0068：手动场景原事务许可、幂等拒绝及真实PG锁序，不以外包测试事务替代应用代理。 */
@Import(RuleSceneLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"SCENE_POSTGRES"})
class RuleSceneLifecycleTests extends AbstractIntegrationTest {
    /** 专库避免异步规则、审计和历史场景事实互相污染。 */
    private static final String DATABASE_NAME = "rule_scene_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 沿原真实镜像和角色，仅物理数据库独占。 */
    private static final PostgreSQLContainer<?> SCENE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Spring连接池与Flyway在初始化前共同切换专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 完整行比较同时覆盖执行、动作和审计；相同计数不能掩盖已有行被覆盖。 */
    private static final List<String> FACT_TABLES = List.of("rule_scene_execution", "rule_device_action_delivery",
            "rule_notification_delivery", "ts_device_command", "ts_device_command_attempt", "sys_outbox_event", "sys_audit_log");
    /** 父runner固定共享库，专库不能借其改变其他测试额度。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuota;
    /** 本片不运行任务自动扫描。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 只检查通知Outbox原子提交，外发另有独立合同。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 排除无关后台属性维护。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 排除无关命令超时维护。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedTimeoutScanner;
    /** 唯一执行入口是原真实Spring事务服务。 */
    @Autowired private RuleSceneService scenes;
    /** spy只围绕原SQL设屏障、取身份，绝不stub成功许可。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 同事务物理PID与APP角色观察。 */
    @Autowired private JdbcTemplate jdbc;
    /** 迁移落点核验。 */
    @Autowired private Environment environment;
    /** 原应用JSON配置。 */
    @Autowired private ObjectMapper mapper;
    /** 每例新项目及设备，历史不可变审计由专库回收。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 原服务创建并发布的合法场景。 */
    private RuleSceneView scene;
    /** 原许可前可记录等待PID；默认无附加行为。 */
    private volatile Runnable beforePermit = () -> { };
    /** 原许可真实成功之后才允许暂停，防止用快照假装已持锁。 */
    private volatile Runnable afterPermit = () -> { };
    /** 捕获跨租户场景实际传入公开许可的owner身份。 */
    private volatile UUID permitTenant;

    /** 专库、真实OWNER、合法设备与活动场景均成功后，才装配不改变返回值的观察器。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        scene = asOwner(() -> {
            RuleSceneView created = scenes.create(fixture.projectId(), new CreateSceneCommand("许可验收场景", null,
                    List.of(), List.of(new ActionSpec("notification-action", mapper.createObjectNode()
                    .put("channel", "email").put("recipient", "scene@example.com")
                    .put("subject", "场景通知").put("body", "原事务验收")))));
            UUID version = scenes.versions(fixture.projectId(), created.id()).getFirst().id();
            return scenes.activate(fixture.projectId(), created.id(), version, created.version());
        });
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            actualAppPid();
            permitTenant = invocation.getArgument(0);
            beforePermit.run();
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(target).requireActiveForWrite(any(), any());
    }

    /** 旧生产精确RED：合法场景归档应返回50017，而非旧调度上下文提前返回50001。 */
    @Test
    void archivedProjectRejectsBeforeExecutionActionAndAudit() throws Exception {
        archive();
        Map<String, List<String>> before = facts();
        assertReadOnly(catchThrowable(() -> execute("archived")));
        assertThat(facts()).isEqualTo(before);
        assertThat(before.get("rule_scene_execution")).isEmpty();
        assertThat(before.get("sys_outbox_event")).isEmpty();
    }

    /** 成功事实保留，但冻结期间原写入口不能通过同键幂等回读绕过资格。 */
    @Test
    void archivedProjectRejectsExistingIdempotencyKeyWithoutChangingFacts() throws Exception {
        RuleSceneExecutionView first = execute("replay");
        archive();
        Map<String, List<String>> before = facts();
        assertReadOnly(catchThrowable(() -> execute("replay")));
        assertThat(facts()).isEqualTo(before);
        assertThat(mapper.readTree(before.get("rule_scene_execution").getFirst()).path("id").asText())
                .isEqualTo(first.id().toString());
    }

    /** 正常ACTIVE必须真实派发一次；同键第二次返回同一执行且不追加审计或动作。 */
    @Test
    void activeProjectCommitsOneExecutionAndReplaysIdentically() throws Exception {
        RuleSceneExecutionView first = execute("normal");
        Map<String, List<String>> before = facts();
        assertThat(first.status()).isEqualTo("DISPATCHED");
        assertThat(before.get("rule_scene_execution")).hasSize(1);
        assertThat(before.get("sys_outbox_event")).hasSize(1);
        assertThat(execute("normal").id()).isEqualTo(first.id());
        assertThat(facts()).isEqualTo(before);
        assertThat(permitTenant).isEqualTo(fixture.tenantId());
    }

    /** 跨租户ADMIN的JWT租户不同，许可与持久事实必须使用真实项目ownerTenant。 */
    @Test
    void collaboratorUsesOwnerTenantForPermitAndCommittedFacts() throws Exception {
        UUID tenant = Uuid7.generate();
        UUID account = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            update(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '协作者租户')", tenant);
            update(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '协作者')", account, account + "@example.com");
            update(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), tenant, account);
            update(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'ADMIN')", Uuid7.generate(), fixture.projectId(), account);
            owner.commit();
        }
        RuleSceneExecutionView result = inScope(tenant, account, () -> scenes.execute(fixture.projectId(), scene.id(), "collaborator", command()));
        assertThat(result.status()).isEqualTo("DISPATCHED");
        assertThat(result.operatorAccountId()).isEqualTo(account);
        assertThat(permitTenant).isEqualTo(fixture.tenantId()).isNotEqualTo(tenant);
        Map<String, List<String>> current = facts();
        assertThat(mapper.readTree(current.get("rule_scene_execution").getFirst()).path("tenant_id").asText()).isEqualTo(fixture.tenantId().toString());
        assertThat(current.get("sys_outbox_event")).hasSize(1);
    }

    /** 许可先得时归档真实阻塞，直到原场景执行、动作和审计全部提交后才能冻结。 */
    @Test
    void projectPermitBlocksArchiveUntilOriginalSceneTransactionCommits() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> archivePid = new CompletableFuture<>();
        afterPermit = () -> { writerPid.complete(actualAppPid()); await(release); };
        Map<String, List<String>> before = facts();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuleSceneExecutionView> writer = executor.submit(() -> execute("permit-first"));
            int pid = writerPid.get(4, TimeUnit.SECONDS);
            Future<?> freezer = executor.submit(() -> archive(archivePid));
            assertBlockedBy(archivePid.get(3, TimeUnit.SECONDS), pid, freezer);
            assertThat(facts()).isEqualTo(before);
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS).status()).isEqualTo("DISPATCHED");
            freezer.get(5, TimeUnit.SECONDS);
            assertThat(facts().get("rule_scene_execution")).hasSize(1);
            assertThat(facts().get("sys_outbox_event")).hasSize(1);
        } finally { finish(executor, release); }
    }

    /** 未提交归档先锁住项目：场景等待真正提交后重读并拒绝，不能使用授权阶段ACTIVE快照。 */
    @Test
    void archiveCommitDuringPermitWaitIsRecheckedAndRejected() throws Exception {
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        beforePermit = () -> writerPid.complete(actualAppPid());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            update(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            Map<String, List<String>> before = facts();
            Future<Throwable> writer = executor.submit(() -> catchThrowable(() -> execute("freeze-first")));
            try {
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
                holder.commit();
                assertReadOnly(writer.get(5, TimeUnit.SECONDS));
                assertThat(facts()).isEqualTo(before);
            } finally { holder.rollback(); }
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** 真实五秒语句超时必须保留SQL57014且无业务写；锁释放后同一幂等请求可重新成功。 */
    @Test
    void realPermitSqlTimeoutRollsBackAndSameRequestRecovers() throws Exception {
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        beforePermit = () -> writerPid.complete(actualAppPid());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Map<String, List<String>> before = facts();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            update(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            Future<Throwable> writer = executor.submit(() -> catchThrowable(() -> execute("timeout")));
            try {
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
                Throwable failure = writer.get(8, TimeUnit.SECONDS);
                assertThat(sqlState(failure)).isEqualTo("57014");
                assertThat(facts()).isEqualTo(before);
            } finally { holder.rollback(); }
        } finally { finish(executor, new CountDownLatch(0)); }
        beforePermit = () -> { };
        assertThat(execute("timeout").status()).isEqualTo("DISPATCHED");
        assertThat(facts().get("rule_scene_execution")).hasSize(1);
        assertThat(facts().get("sys_outbox_event")).hasSize(1);
    }

    /** 输入固定且合法，测试冻结边界而非参数校验。 */
    private ExecuteSceneCommand command() { return new ExecuteSceneCommand(fixture.deviceId(), mapper.createObjectNode()); }
    /** 原HTTP范围不创建事务；事务由场景应用服务自己开启。 */
    private RuleSceneExecutionView execute(String key) { return asOwner(() -> scenes.execute(fixture.projectId(), scene.id(), key, command())); }
    /** owner身份和项目范围只来自真实夹具。 */
    private <T> T asOwner(Supplier<T> action) { return inScope(fixture.tenantId(), fixture.accountId(), action); }
    /** 非owner租户用于协作授权证明，不以管理员连接执行应用入口。 */
    private <T> T inScope(UUID tenant, UUID account, Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenant, fixture.projectId(), account));
        try { return action.get(); } finally { TenantContext.clear(); }
    }
    /** 明确项目公开码，不把事务已标记回滚的UnexpectedRollback当作预期拒绝。 */
    private void assertReadOnly(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                rejected -> assertThat(rejected.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_READ_ONLY));
    }
    /** 真实许可前后只能观察原APP写事务，不能autocommit瞬时取锁。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }
    /** 归档没有业务API；仅独立事务更新已定义的status字段并确认提交。 */
    private void archive() { archive(new CompletableFuture<>()); }
    /** 发布owner PID以证明真正等待项目行锁。 */
    private void archive(CompletableFuture<Integer> pid) {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            pid.complete(connectionPid(owner));
            update(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            owner.commit();
        } catch (SQLException failure) { throw new IllegalStateException("独立归档失败", failure); }
    }
    /** 所有快照从专库owner物理表读取，不能被RLS空结果伪造零写。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5); query.setObject(1, fixture.projectId());
                    List<String> rows = new ArrayList<>();
                    try (ResultSet values = query.executeQuery()) { while (values.next()) rows.add(values.getString(1)); }
                    result.put(table, List.copyOf(rows));
                }
            }
        }
        return result;
    }
    /** 完整合法关系使用实际外键与成员授权，不制造没有设备或owner的UUID。 */
    private void seed() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            update(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '场景生命周期租户')", fixture.tenantId());
            update(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '场景OWNER')", fixture.accountId(), fixture.accountId() + "@example.com");
            update(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            update(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '场景许可验收', 'sh-1', ?)", fixture.projectId(), fixture.tenantId(), "scene_" + fixture.projectId().toString().replace("-", ""));
            update(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.accountId());
            update(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'scene_type', '场景类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", fixture.typeId(), fixture.tenantId(), fixture.projectId());
            update(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'scene_device', '场景设备', 'ONLINE')", fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
    }
    /** Runtime全部池及迁移/owner同时核验，防止专库只有URL文本相同。 */
    private void verifyIsolation() throws SQLException {
        assertThat(AopUtils.isAopProxy(scenes)).isTrue();
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"), environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
             Connection owner = fixtureOwnerConnection()) {
            for (Connection connection : List.of(migration, owner)) {
                try (PreparedStatement query = connection.prepareStatement("SELECT current_database(), current_user"); ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                    assertThat(rows.getString(2)).isEqualTo(SCENE_POSTGRES.getUsername());
                }
            }
        }
    }
    /** 真实PG等待关系和未授予锁同时成立，不能用线程尚未完成替代锁证据。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?)), EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            query.setQueryTimeout(1); query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); if (rows.getBoolean(1) && rows.getBoolean(2)) return; }
                if (operation.isDone()) throw new AssertionError("业务已结束，未观察到指定PG阻塞");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定APP事务的真实锁等待");
    }
    /** 原SQLSTATE沿cause读取，拒绝码与连接/超时故障不得混淆。 */
    private String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }
    /** 连接PID取自PG，保持与业务事务PID可比。 */
    private int connectionPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getInt(1); }
    }
    /** 等待屏障有界，中断不被吞掉。 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("业务屏障已释放").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    /** 所有线程退出先释放屏障再等待，禁止带运行中的应用事务进入下一例。 */
    private void finish(ExecutorService executor, CountDownLatch release) throws InterruptedException {
        release.countDown(); executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        TenantContext.clear();
    }
    /** 不可变执行及审计全部保留到专库回收，不关闭触发器或清除历史事实。 */
    @AfterEach
    void cleanupScope() { TenantContext.clear(); beforePermit = () -> { }; afterPermit = () -> { }; }
    /** 所有owner访问必须落本类专库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException { return DriverManager.getConnection(DATABASE_URL, SCENE_POSTGRES.getUsername(), SCENE_POSTGRES.getPassword()); }
    /** 每个夹具写都必须真实影响一行，SQL参数化且保持有界预算。 */
    private void update(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
    /** 专库由本类OwnedTestContainers统一回收。 */
    private static String startDatabase() { SCENE_POSTGRES.start(); return SCENE_POSTGRES.getJdbcUrl(); }
    /** 仅覆盖物理地址并暂停无关自动消费者，不替换业务入口。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 在连接池初始化前同步Runtime与Flyway地址。 */
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
    /** 全部身份对应专库真实行。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId) { }
}
