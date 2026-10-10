package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.AlarmNotificationDeliveryService;
import com.things.link.alarm.application.AlarmPushAudiencePort;
import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionResult;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.infrastructure.persistence.JdbcAlarmPushAudienceAdapter;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0064决策4、S12-P0-5c3a：受众门禁必须加入真实告警激活事务并持续保护PUSH投递及对应Outbox。
 * 原额度查询先检查ACTIVE；确定性竞态在其真实成功后冻结项目，不伪造冻结项目额度成功的错误前提。
 * 独占PG防止同JVM其他缓存上下文的全局publisher领取本例Outbox；随机项目不能隔离这些后台线程。
 */
@Import(AlarmPushAudienceProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"AUDIENCE_POSTGRES"})
class AlarmPushAudienceProjectLifecycleTests extends AbstractIntegrationTest {

    /** D-109/CI96：以不同数据库实际落点隔离全局领取者，不能仅比较随机项目ID。 */
    private static final String DATABASE_NAME = "alarm_audience_" + UUID.randomUUID().toString().replace("-", "");
    /** 角色是PG集群级事实，沿用相同镜像与owner但独占容器，避免迁移污染共享角色。 */
    private static final PostgreSQLContainer<?> AUDIENCE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring运行连接、Flyway与所有owner夹具共用此实际地址。 */
    private static final String DATABASE_URL = startIsolatedDatabase();
    /** 父类runner明确直连共享POSTGRES；本类不用REST夹具，不执行这条跨库配额修改。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 本类只验收意图事务；协调器可直接领取QUEUED行，禁publisher不能代替隔离这个本库领取者。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;


    /** 被测适配器自身不能创建事务，只能复用原调用方事务许可。 */
    @Autowired private JdbcAlarmPushAudienceAdapter audience;
    /** 显式告警动作触发真正的instance/event/notification/outbox原生产事务。 */
    @Autowired private RuleAlarmActionService alarmActions;
    /** 建立真实AES-GCM安装事实，不手造信封。 */
    @Autowired private AppPushTokenService pushTokens;
    /** OWNER真实删除提供冻结提交点。 */
    @Autowired private ProjectService projectService;
    /** 原连接PID、RLS与真实投递事实观察。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 仅adapter独立合同和删除提交使用TT，告警动作从不包外层测试事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 用户种子沿真实哈希格式。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 真实额度查询后暂停，不修改返回值，也不绕过已冻结前置拒绝。 */
    @MockitoSpyBean private ProjectDailyQuotaDecisionService quotas;
    /** 在原PUSH投递与Outbox已写完之后观察原告警事务仍然持锁。 */
    @MockitoSpyBean private AlarmNotificationDeliveryService deliveries;
    /** 真实许可SQL前观察PID，SQL仍按生产路径执行。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 只在真实OWNER删除的原角色查询之后采样PID，不人为提供外层事务或替换管理锁。 */
    @MockitoSpyBean private JdbcProjectRepository projects;
    /** 每例仅有一个PUSH路由、一个有效设备关系及安装，精确隔离候选身份。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 真实安装稳定ID，只以ID参与受众和投递，不把厂商凭据放入意图。 */
    private UUID pushTokenId;
    /** 同一输入支持回滚后重试，并以messageId核验不可变ACTIVATED事件。 */
    private final Instant receivedAt = Instant.now();

    /** 最小夹具只种控制面前置；后台激活、额度和安装均使用生产路径。 */
    @BeforeEach
    void prepare() throws SQLException {
        verifyDatabaseIsolationBeforeFixtureWrites();
        seedFixture();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            pushTokens.register(fixture.tenantId(), fixture.projectId(), fixture.userId(), fixture.installationId(),
                    AppPushToken.Provider.MOCK, "audience-lifecycle-token");
        } finally { RlsScopeContext.clear(); }
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT id FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?")) {
            query.setObject(1, fixture.tenantId()); query.setObject(2, fixture.userId()); query.setObject(3, fixture.installationId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue(); pushTokenId = rows.getObject(1, UUID.class);
                assertThat(rows.next()).isFalse();
            }
        }
        assertThat(directAudience(fixture.tenantId())).containsExactly(new AlarmPushAudiencePort.PushAudience(fixture.userId(), pushTokenId));
    }

    /** 已提交的账号关闭偏好在原授权读取中生效，重新开启不改安装与设备关系。 */
    @Test
    void accountPreferenceSuppressesPushAndRestoresOnlyAfterEnabled() throws Exception {
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO app_notification_preference(tenant_id,app_user_id,app_push_enabled,revision) VALUES(?,?,false,1)", fixture.tenantId(), fixture.userId());
        }
        assertThat(directAudience(fixture.tenantId())).isEmpty();
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE app_notification_preference SET app_push_enabled=true,revision=2 WHERE tenant_id=? AND app_user_id=?", fixture.tenantId(), fixture.userId());
        }
        assertThat(directAudience(fixture.tenantId())).isNotEmpty();
    }

    /** 专库先于Spring普通单例与Flyway初始化，本类结束且Spring物理关闭后由OwnedTestContainers回收。 */
    private static String startIsolatedDatabase() {
        AUDIENCE_POSTGRES.start();
        return AUDIENCE_POSTGRES.getJdbcUrl();
    }

    /** 动工前验证两个连接池和owner实际落点及RLS权限，不把配置字符串当作隔离证据。 */
    private void verifyDatabaseIsolationBeforeFixtureWrites() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        // 具名且enforceOverride的mock确实替代父runner；不依赖首次启动调用记录，避免Mockito每例重置造成假失败。
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(mockingDetails(unusedNotificationWorkCoordinator).isMock()).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT current_database(), current_user")) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME).isNotEqualTo(POSTGRES.getDatabaseName());
                assertThat(rows.getString(2)).isEqualTo(AUDIENCE_POSTGRES.getUsername());
            }
            // 本类不运行worker；每例精确清理后的全库应没有可被其他上下文误领的剩余Outbox。
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event")).isZero();
        }
    }

    /** adapter在真实非只读事务匹配ACTIVE，冻结或错tenant仅返回空受众，不改变任何事实。 */
    @ParameterizedTest
    @EnumSource(ProjectState.class)
    void adapterMatchesLifecycleInExistingTransaction(ProjectState state) throws Exception {
        List<String> before = facts();
        applyState(state);
        List<AlarmPushAudiencePort.PushAudience> result = directAudience(state == ProjectState.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId());
        if (state == ProjectState.ACTIVE) assertThat(result).containsExactly(new AlarmPushAudiencePort.PushAudience(fixture.userId(), pushTokenId));
        else assertThat(result).isEmpty();
        assertThat(facts()).isEqualTo(before);
    }

    /** MANDATORY不替调用方造事务，已有只读事务也属于编程前置错误。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adapterRejectsMissingOrReadOnlyTransaction(boolean readOnly) throws Exception {
        List<String> before = facts();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            Throwable failure;
            if (readOnly) {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setReadOnly(true);
                failure = catchThrowable(() -> transaction.execute(status -> audience.listActiveInstallations(fixture.tenantId(), fixture.projectId(), fixture.deviceId())));
                assertThat(failure).isExactlyInstanceOf(IllegalStateException.class);
            } else {
                failure = catchThrowable(() -> audience.listActiveInstallations(fixture.tenantId(), fixture.projectId(), fixture.deviceId()));
                assertThat(failure).isExactlyInstanceOf(IllegalTransactionStateException.class);
            }
        } finally { RlsScopeContext.clear(); }
        assertThat(facts()).isEqualTo(before);
    }

    /** 正常原告警路径必须真的生成PUSH与唯一对应Outbox，不能仅验证适配器返回非空。 */
    @Test
    void activeAlarmCreatesPushDeliveryAndMatchingOutbox() throws Exception {
        assertThat(activate().changed()).isTrue();
        assertAlarmAndPush(1);
    }

    /** 已冻结项目在进入受众前就被原额度合同拒绝；测试不能mock NORMAL绕过这一真实前置。 */
    @ParameterizedTest
    @EnumSource(value = ProjectState.class, names = {"ARCHIVED", "DELETED"})
    void alreadyFrozenAlarmStillFailsOriginalQuotaPrecondition(ProjectState state) throws Exception {
        applyState(state);
        List<String> before = facts();
        assertThat(catchThrowable(this::activate)).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("项目与租户归属不匹配或项目不可用");
        assertThat(facts()).isEqualTo(before);
    }

    /** 额度真实返回NORMAL后，非键归档提交；竞争方是测试owner SQL而非新增归档API，原告警不得展开PUSH。 */
    @Test
    void archiveCommittedAfterRealQuotaDecisionSuppressesPushInOriginalAlarmTransaction() throws Exception {
        CountDownLatch quotaReady = new CountDownLatch(1);
        CountDownLatch continueAlarm = new CountDownLatch(1);
        pauseAfterRealQuota(quotaReady, continueAlarm);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuleAlarmActionResult> activation = executor.submit(this::activate);
            assertThat(quotaReady.await(5, TimeUnit.SECONDS)).isTrue();
            applyState(ProjectState.ARCHIVED);
            assertArchived();
            continueAlarm.countDown();
            assertThat(activation.get(8, TimeUnit.SECONDS).changed()).isTrue();
            // 本片只阻止新PUSH，已有原业务ACTIVATED事实仍按原合同提交。
            assertAlarmAndPush(0);
        } finally {
            continueAlarm.countDown(); shutdown(executor);
        }
    }

    /**
     * V20260810_0100的instance/event外键在额度前已持有项目KEY SHARE，5d4真实删除必须等待。
     * 释放后仍由原告警取得受众SHARE并提交；两个Future都必须成功，不能预设锁升级不会死锁。
     */
    @Test
    void deletionAfterRealQuotaWaitsForOriginalAlarmTransactionToCommit() throws Exception {
        CountDownLatch quotaReady = new CountDownLatch(1);
        CountDownLatch continueAlarm = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = pauseAfterRealQuota(quotaReady, continueAlarm);
        CompletableFuture<Integer> permitPid = capturePermitPid();
        List<String> before = facts();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuleAlarmActionResult> activation = executor.submit(this::activate);
            assertThat(quotaReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(permitPid.isDone()).as("额度屏障尚未进入受众许可").isFalse();
            assertThat(facts()).isEqualTo(before);
            CompletableFuture<Integer> deletionPid = captureDeletionPid();
            Future<?> deletion = executor.submit(this::deleteAsOwner);
            assertBlockedBy(deletionPid.get(3, TimeUnit.SECONDS), writerPid.get(3, TimeUnit.SECONDS), deletion);
            continueAlarm.countDown();
            assertThat(activation.get(8, TimeUnit.SECONDS).changed()).isTrue();
            assertThat(permitPid.get(3, TimeUnit.SECONDS)).isEqualTo(writerPid.get(3, TimeUnit.SECONDS));
            deletion.get(8, TimeUnit.SECONDS);
            assertDeleted(); assertAlarmAndPush(1);
        } finally { continueAlarm.countDown(); shutdown(executor); }
    }

    /** 受众返回后真实投递和Outbox都已写完，原告警事务未提交时OWNER删除仍必须等待。 */
    @Test
    void realPushWritesKeepProjectPermitUntilAlarmCommit() throws Exception {
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        afterRealDelivery(() -> {
            writerPid.complete(appPid()); written.countDown(); awaitRelease(release);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuleAlarmActionResult> activation = executor.submit(this::activate);
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(facts()).isEqualTo(before);
            CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, null, null));
            assertBlockedBy(deletionPid.get(3, TimeUnit.SECONDS), writerPid.get(3, TimeUnit.SECONDS), deletion);
            release.countDown();
            assertThat(activation.get(8, TimeUnit.SECONDS).changed()).isTrue();
            deletion.get(8, TimeUnit.SECONDS);
            assertDeleted(); assertAlarmAndPush(1);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 非键归档兼容已有外键KEY SHARE但阻塞受众SHARE；这是owner SQL竞争方，不是新增归档API。 */
    @Test
    void archiveInFlightMakesOriginalAlarmPermitWaitThenReturnEmptyAudience() throws Exception {
        CountDownLatch quotaReady = new CountDownLatch(1);
        CountDownLatch continueAlarm = new CountDownLatch(1);
        pauseAfterRealQuota(quotaReady, continueAlarm);
        CompletableFuture<Integer> writerPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection archiver = ownerConnection()) {
            archiver.setAutoCommit(false);
            try {
                Future<RuleAlarmActionResult> activation = executor.submit(this::activate);
                assertThat(quotaReady.await(5, TimeUnit.SECONDS)).isTrue();
                execute(archiver, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
                int archivePid = (int) count(archiver, "SELECT pg_backend_pid()");
                continueAlarm.countDown();
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), archivePid, activation);
                archiver.commit();
                assertThat(activation.get(8, TimeUnit.SECONDS).changed()).isTrue();
                assertArchived(); assertAlarmAndPush(0);
            } finally { continueAlarm.countDown(); archiver.rollback(); shutdown(executor); }
        }
    }

    /** 原告警事务在受众锁SQL被57014取消后整体回滚，包括之前已写的instance/event；解锁后同message可重试。 */
    @Test
    void projectPermitTimeoutRollsBackOriginalAlarmTransactionAndRecovers() throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        List<String> before = facts();
        CountDownLatch quotaReady = new CountDownLatch(1);
        CountDownLatch continueAlarm = new CountDownLatch(1);
        pauseAfterRealQuota(quotaReady, continueAlarm);
        CompletableFuture<Integer> writerPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                Future<Throwable> activation = executor.submit(() -> catchThrowable(this::activate));
                assertThat(quotaReady.await(5, TimeUnit.SECONDS)).isTrue();
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                continueAlarm.countDown();
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), holderPid, activation);
                Throwable failure = activation.get(10, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(failure)).as("原告警许可SQL应被JDBC预算取消：%s", failure).isTrue();
                assertThat(failure).isNotInstanceOf(BusinessException.class);
                assertThat(facts()).isEqualTo(before);
                holder.rollback();
                assertThat(activate().changed()).isTrue();
                assertAlarmAndPush(1);
            } finally { continueAlarm.countDown(); holder.rollback(); shutdown(executor); }
        }
    }

    /** 真正instance/event/PUSH/outbox全部写完后内部异常仍整体回滚，不把半成功通知留下。 */
    @Test
    void internalFailureAfterRealPushWritesRollsBackAllAlarmFacts() throws Exception {
        List<String> before = facts();
        afterRealDelivery(() -> { throw new InjectedFailure(); });
        assertThat(catchThrowable(this::activate)).isExactlyInstanceOf(InjectedFailure.class);
        assertThat(facts()).isEqualTo(before);
    }

    /** 独立端口合同仅以TT提供调用方事务；端到端证据另走activate原代理，不能混淆。 */
    private List<AlarmPushAudiencePort.PushAudience> directAudience(UUID tenantId) {
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                appPid();
                return audience.listActiveInstallations(tenantId, fixture.projectId(), fixture.deviceId());
            });
        } finally { RlsScopeContext.clear(); }
    }

    /** 与RuleExecutionCoordinator一样使用真实规则配置者作为后台数据范围，不伪造App actor。 */
    private RuleAlarmActionResult activate() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            return alarmActions.create(new RuleAlarmActionInput(fixture.messageId(), fixture.tenantId(), fixture.projectId(),
                    fixture.ruleId(), fixture.deviceId(), receivedAt, receivedAt, "push-life-" + fixture.messageId()));
        } finally {
            TenantContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    /** 真实函数返回NORMAL后公布原APP PID，验证已写事件但未展开投递；原始额度前置和事务均保持。 */
    private CompletableFuture<Integer> pauseAfterRealQuota(CountDownLatch quotaReady, CountDownLatch release) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectDailyQuotaDecisionService target = AopTestUtils.getUltimateTargetObject(quotas);
        doAnswer(invocation -> {
            ProjectDailyQuotaDecisionService.Decision result =
                    (ProjectDailyQuotaDecisionService.Decision) invocation.callRealMethod();
            assertThat(result.status()).isEqualTo(QuotaStatus.NORMAL);
            assertThat(result.disabled()).isFalse();
            pid.complete(appPid());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM alarm_event WHERE project_id = ? AND source_message_id = ? AND event_type = 'ACTIVATED'",
                    Integer.class, fixture.projectId(), fixture.messageId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM alarm_notification_delivery WHERE project_id = ?",
                    Integer.class, fixture.projectId())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = 'ALARM_NOTIFICATION_DELIVERY_REQUEST'",
                    Integer.class, fixture.projectId())).isZero();
            quotaReady.countDown(); awaitRelease(release);
            return result;
        }).when(target).decisionTrustedProject(fixture.tenantId(), fixture.projectId(), QuotaMetric.NOTIFICATION_DELIVERY);
        return pid;
    }

    /** 真实展开已返回但外层RuleAlarmActionService尚未提交；验证当前物理连接能见完整意图和Outbox。 */
    private void afterRealDelivery(Runnable afterWrite) {
        AlarmNotificationDeliveryService target = AopTestUtils.getUltimateTargetObject(deliveries);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            appPid();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM alarm_notification_delivery WHERE project_id = ? AND channel = 'PUSH' AND status = 'QUEUED'", Integer.class, fixture.projectId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event o JOIN alarm_notification_delivery d ON d.last_outbox_event_id = o.id WHERE d.project_id = ? AND d.channel = 'PUSH' AND o.aggregate_id = d.id", Integer.class, fixture.projectId())).isEqualTo(1);
            afterWrite.run();
            return null;
        }).when(target).createForActivatedEvent(argThat(instance -> instance != null && instance.projectId().equals(fixture.projectId())), any(AlarmEvent.class));
    }

    /** 只设置归档种子；删除经过真实OWNER服务。 */
    private void applyState(ProjectState state) throws SQLException {
        if (state == ProjectState.DELETED) { deleteAsOwner(); assertDeleted(); }
        else if (state == ProjectState.ARCHIVED) {
            try (Connection owner = ownerConnection()) { execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId()); }
        }
    }

    /** 屏障不吞中断，失败时工作线程必须被finally收束。 */
    private void awaitRelease(CountDownLatch release) {
        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("告警事务屏障被中断", failure); }
    }

    /** 真实许可前只公布连接，原SQL会自行加锁或在原预算取消。 */
    private CompletableFuture<Integer> capturePermitPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> { pid.complete(appPid()); return invocation.callRealMethod(); })
                .when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        return pid;
    }

    /** 首次原角色SQL成功后采样真实删除事务；后续普通查询不再被额外事务断言干扰。 */
    private CompletableFuture<Integer> captureDeletionPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(projects);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (!pid.isDone()) pid.complete(appPid());
            return result;
        }).when(target).findRole(fixture.projectId(), fixture.accountId());
        return pid;
    }

    /** 观察必须处于真实非只读APP事务，不能拿owner连接冒充原调用方。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 普通OWNER删除由生产代理自行提交。 */
    private void deleteAsOwner() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { projectService.delete(fixture.projectId()); }
        finally {
            TenantContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    /** 并发用例只为删除建立真实外层事务以控制提交；告警激活没有测试事务包裹。 */
    private void deleteTransaction(CompletableFuture<Integer> pid, CountDownLatch deleted, CountDownLatch release) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                pid.complete(appPid());
                projectService.delete(fixture.projectId());
                if (deleted != null) {
                    deleted.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
            });
        } finally { TenantContext.clear(); }
    }

    /** 三个独立PID、阻塞者和未授予锁证明真实排序，不只观察Future尚未完成。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("操作已完成却未观察到预期项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目许可锁等待");
    }

    /** 真实归档提交且关系/安装仍有效，空受众只能来自项目资格；归档不触发软删。 */
    private void assertArchived() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ARCHIVED' AND deleted_at IS NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND status = 'ACTIVE'", fixture.tenantId(), fixture.userId())).isEqualTo(1);
        }
    }

    /** 项目已冻结但用户、设备关系和安装仍保留，空受众不能归因于夹具消失。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND status = 'ACTIVE'", fixture.tenantId(), fixture.userId())).isEqualTo(1);
        }
    }

    /** 同一source唯一ACTIVATED保持后台actor为空；PUSH计数与精确delivery/outbox身份关联必须一致。 */
    private void assertAlarmAndPush(int expectedPush) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM alarm_instance WHERE project_id = ? AND rule_id = ? AND condition_state = 'ACTIVE'", fixture.projectId(), fixture.ruleId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM alarm_event WHERE project_id = ? AND source_message_id = ? AND event_type = 'ACTIVATED' AND actor_id IS NULL", fixture.projectId(), fixture.messageId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE project_id = ? AND channel = 'PUSH'", fixture.projectId())).isEqualTo(expectedPush);
            assertThat(count(owner, """
                    SELECT count(*) FROM alarm_notification_delivery d
                    JOIN alarm_event e ON e.id = d.alarm_event_id AND e.project_id = d.project_id
                    JOIN sys_outbox_event o ON o.id = d.last_outbox_event_id
                    WHERE d.project_id = ? AND d.channel = 'PUSH' AND d.status = 'QUEUED'
                      AND d.app_user_id = ? AND d.push_token_id = ? AND d.recipient_id IS NULL
                      AND d.target_snapshot = 'PUSH' AND e.source_message_id = ?
                      AND o.project_id = d.project_id AND o.aggregate_id = d.id
                      AND o.aggregate_type = 'ALARM_NOTIFICATION_DELIVERY' AND o.event_type = 'ALARM_NOTIFICATION_DELIVERY_REQUEST'
                    """, fixture.projectId(), fixture.userId(), pushTokenId, fixture.messageId())).isEqualTo(expectedPush);
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND event_type = 'ALARM_NOTIFICATION_DELIVERY_REQUEST'", fixture.projectId())).isEqualTo(expectedPush);
        }
    }

    /** 比较本例完整业务行，区分真实告警回滚与仅PUSH抑制；不把项目状态/删除日志混入快照。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("alarm_instance", "alarm_event", "alarm_notification_delivery", "sys_outbox_event", "app_user_role", "app_user_device")) {
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM " + table + " t WHERE project_id = ? ORDER BY id")) {
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(table + rows.getString(1)); }
                }
            }
            try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM app_push_token t WHERE tenant_id = ? AND app_user_id = ? ORDER BY id")) {
                query.setObject(1, fixture.tenantId()); query.setObject(2, fixture.userId());
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add("app_push_token" + rows.getString(1)); }
            }
        }
        return List.copyOf(result);
    }

    /** 只接受PG query_canceled，不把连接错误或业务拒绝当锁超时。 */
    private boolean isSqlTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 各测试先释放持锁事务/屏障，再收束线程，失败不能泄漏后台写入。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 适配器资格矩阵，不将ARCHIVED当普通读取放行。 */
    private enum ProjectState {
        /** 写许可正常。 */ ACTIVE,
        /** 只读项目不给新PUSH。 */ ARCHIVED,
        /** OWNER删除后冻结。 */ DELETED,
        /** 显式tenant不符。 */ WRONG_TENANT
    }

    /** 原事务内部故障，避免SQL异常翻译干扰注入点的回滚验证。 */
    private static final class InjectedFailure extends RuntimeException {
        /** 固定测试异常版本。 */ private static final long serialVersionUID = 1L;
        /** 不携带通知内容或凭据。 */ private InjectedFailure() { super("真实PUSH意图写入后的受控内部故障"); }
    }

    /** 有效设备、关系与PUSH路由按真实schema构造；额度使用默认真实FREE策略，不mock仓储。 */
    private void seedFixture() throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '告警受众生命周期租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '告警规则owner')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, 'PUSH受众项目', 'sh-1', ?)", fixture.projectId(), fixture.tenantId(), "push_audience_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES (?, ?, 'alarm_audience', ?, 'ACTIVE')", fixture.userId(), fixture.tenantId(), passwordEncoder.encode("secret123"));
            execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'OBSERVER', 'ACTIVE')", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId());
            execute(owner, "INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status) VALUES (?, ?, ?, 'push_audience_type', '受众设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status) VALUES (?, ?, ?, ?, 'push_audience_device', '受众设备', 'ONLINE')", fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_property_definition (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type) VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role, status) VALUES (?, ?, ?, ?, ?, 'PRIMARY', 'ACTIVE')", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId(), fixture.deviceId());
            execute(owner, """
                    INSERT INTO alarm_rule (id, tenant_id, project_id, name, alarm_type, originator_type, originator_id,
                        property_key, trigger_operator, trigger_threshold, clear_operator, clear_threshold, severity)
                    VALUES (?, ?, ?, '生命周期受众规则', 'LIFECYCLE_PUSH', 'DEVICE', ?, 'temperature', 'GT', 30, 'LT', 25, 'WARNING')
                    """, fixture.ruleId(), fixture.tenantId(), fixture.projectId(), fixture.deviceId());
            execute(owner, "INSERT INTO alarm_notification_group (id, tenant_id, project_id, name) VALUES (?, ?, ?, 'PUSH受众组')", fixture.groupId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO alarm_notification_template (id, tenant_id, project_id, name, channel, subject_template, body_template) VALUES (?, ?, ?, 'PUSH受众模板', 'PUSH', '${alarm.type}', '告警值=${alarm.value}')", fixture.templateId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO alarm_notification_binding (id, tenant_id, project_id, rule_id, group_id, template_id, channel) VALUES (?, ?, ?, ?, ?, ?, 'PUSH')", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.ruleId(), fixture.groupId(), fixture.templateId());
            owner.commit();
        }
    }

    /** 精确移除本例可调度意图与安装；不可变alarm_event及必要FK祖先保留至容器销毁，绝不关闭守卫或删审计。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear(); RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ? AND aggregate_type = 'ALARM_NOTIFICATION_DELIVERY' AND event_type = 'ALARM_NOTIFICATION_DELIVERY_REQUEST'", fixture.projectId());
            execute(owner, "DELETE FROM alarm_notification_delivery WHERE project_id = ? AND channel = 'PUSH'", fixture.projectId());
            execute(owner, "DELETE FROM alarm_notification_binding WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM alarm_notification_template WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM alarm_notification_group WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_push_token WHERE tenant_id = ? AND app_user_id = ?", fixture.tenantId(), fixture.userId());
            execute(owner, "DELETE FROM app_user_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user WHERE id = ?", fixture.userId());
            if (count(owner, "SELECT count(*) FROM alarm_event WHERE project_id = ?", fixture.projectId()) == 0) {
                execute(owner, "DELETE FROM alarm_instance WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM alarm_rule WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_property_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
            owner.commit();
        }
    }

    /** owner只用于种子、独立观察和清理，告警激活与删除始终通过真实APP角色与业务服务。 */
    private Connection ownerConnection() throws SQLException {
        return fixtureOwnerConnection();
    }

    /** 基类可复用播种入口同样指向专库，防止未来增加版本夹具时回落共享数据库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, AUDIENCE_POSTGRES.getUsername(), AUDIENCE_POSTGRES.getPassword());
    }

    /** 参数化SQL固定本例身份，不拼接设备或用户输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 查询必须有结果，不能把数据库错误或不可见行伪装成零。 */
    private long count(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** Registrar晚于继承的DynamicPropertySource注册，防止父类URL覆盖本类物理隔离。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 运行/Flyway统一专库；本类验证创建事务，不启用自动发布、通知重试或Kafka listener。 */
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

    /** @param tenantId 可信owner租户 @param projectId 项目 @param accountId 真实规则配置者/OWNER @param userId App受众 @param typeId 设备类型 @param deviceId 来源设备 @param ruleId 告警规则 @param groupId PUSH组 @param templateId PUSH模板 @param installationId 安装实例 @param messageId 不可变激活来源 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID typeId, UUID deviceId,
                           UUID ruleId, UUID groupId, UUID templateId, UUID installationId, UUID messageId) { }
}
