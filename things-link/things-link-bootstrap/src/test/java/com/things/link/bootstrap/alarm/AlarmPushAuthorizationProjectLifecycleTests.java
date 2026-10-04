package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort;
import com.things.link.alarm.application.NotificationDeliveryExecutionService;
import com.things.link.alarm.application.NotificationDeliveryProperties;
import com.things.link.alarm.application.NotificationRetryScheduler;
import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionResult;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.infrastructure.notification.DeterministicPushNotificationSender;
import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.application.PushTokenCipher;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.infrastructure.persistence.JdbcAlarmPushDeliveryAuthorizationAdapter;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
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
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ADR0064决策4、ADR0038/0051及S12-P0-5c3b：授权短事务持项目许可，真实sender必须在提交后调用。
 * 独占PG并关闭自动调度；请求来自真实告警Outbox、lease来自真实唯一候选领取，不伪造调度或成功状态。
 */
@Import(AlarmPushAuthorizationProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"AUTHORIZATION_POSTGRES"})
class AlarmPushAuthorizationProjectLifecycleTests extends AbstractIntegrationTest {

    /** D-109/CI96：以不同数据库实际落点隔离全局领取者，不能仅比较随机项目ID。 */
    private static final String DATABASE_NAME = "alarm_authorization_" + UUID.randomUUID().toString().replace("-", "");
    /** 角色是PG集群级事实，沿用相同镜像与owner但独占容器，避免迁移污染共享角色。 */
    private static final PostgreSQLContainer<?> AUTHORIZATION_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring运行连接、Flyway与所有owner夹具共用此实际地址。 */
    private static final String DATABASE_URL = startIsolatedDatabase();
    /** 父类runner明确直连共享POSTGRES；本类不用REST夹具，不执行这条跨库配额修改。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 本类手动执行唯一真实领取；协调器可抢领QUEUED行，故单独抑制自动worker而保留真实execution。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;


    /** 被测发送前授权端口；自身不能替调用方创建事务。 */
    @Autowired private JdbcAlarmPushDeliveryAuthorizationAdapter authorization;
    /** 原始告警激活生成合法实例、事件、PUSH投递与Outbox。 */
    @Autowired private RuleAlarmActionService alarmActions;
    /** 安装由生产AES-GCM注册，领域对象不携带明文。 */
    @Autowired private AppPushTokenService pushTokens;
    /** 真正状态推进、授权短事务和事务外sender编排。 */
    @Autowired private NotificationDeliveryExecutionService execution;
    /** 领取/重试调用真实受控函数，不直接设置有效lease。 */
    @Autowired private AlarmNotificationRepository repository;
    /** 原重试生产逻辑追加Outbox所需真实端口。 */
    @Autowired private TransactionalOutboxRepository outbox;
    /** 原通知重试和请求预算不在本类放大。 */
    @Autowired private NotificationDeliveryProperties properties;
    /** 与生产调度共用UTC时钟。 */
    @Autowired private Clock clock;
    /** 读取原持久信封，禁止手拼正确event/attempt代替Outbox证据。 */
    @Autowired private ObjectMapper mapper;
    /** OWNER真实冻结的业务入口。 */
    @Autowired private ProjectService projectService;
    /** 原APP连接、PID与状态观察。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 独立adapter合同与删除提交控制；不包裹deliverClaimed。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 原重试类的真实短事务模板。 */
    @Autowired private TransactionTemplate transactions;
    /** 合法用户种子沿生产口令编码器。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 在真实解密后暂停，拒绝路径必须完全不调用decrypt。 */
    @MockitoSpyBean private PushTokenCipher cipher;
    /** 真正确定性sender仍callRealMethod，只在本例delivery上观察事务边界。 */
    @MockitoSpyBean private DeterministicPushNotificationSender sender;
    /** 原许可SQL前观察PID；超时场景记录原SQL异常但不改变传播。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 两项目同用户共用安装，但受众关系与告警只属于A。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 原告警固定输入时间与唯一source消息。 */
    private final Instant receivedAt = Instant.now();
    /** 原安装稳定ID，授权和投递都必须引用它。 */
    private UUID pushTokenId;
    /** 由原Outbox反序列化的真实首投信封。 */
    private NotificationDeliveryRequest request;
    /** 验证项目冻结/资格变化不会修改其他项目共用安装。 */
    private List<String> originalInstallation;

    /** 全例从真实已排队告警开始；入站创建与发送前授权是独立阶段，不在冻结后伪造额度成功。 */
    @BeforeEach
    void prepare() throws SQLException {
        verifyDatabaseIsolationBeforeFixtureWrites();
        seedFixture();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            pushTokens.register(fixture.tenantId(), fixture.projectId(), fixture.userId(), fixture.installationId(),
                    AppPushToken.Provider.MOCK, "mock:authorization-success");
        } finally { RlsScopeContext.clear(); }
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT id FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?")) {
            query.setObject(1, fixture.tenantId()); query.setObject(2, fixture.userId()); query.setObject(3, fixture.installationId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue(); pushTokenId = rows.getObject(1, UUID.class);
                assertThat(rows.next()).isFalse();
            }
        }
        originalInstallation = installationFacts();
        assertThat(activate().changed()).isTrue();
        request = latestRequest();
        assertState("QUEUED", 0, null);
    }

    /** 专库先于Spring普通单例与Flyway初始化，本类结束且Spring物理关闭后由OwnedTestContainers回收。 */
    private static String startIsolatedDatabase() {
        AUTHORIZATION_POSTGRES.start();
        return AUTHORIZATION_POSTGRES.getJdbcUrl();
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
                assertThat(rows.getString(2)).isEqualTo(AUTHORIZATION_POSTGRES.getUsername());
            }
            // 本类不运行自动worker；每例精确清理后的全库必须无剩余Outbox。
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event")).isZero();
        }
    }

    /** ACTIVE身份在原短事务解密真实AES-GCM信封，授权结果只保留至发送边界。 */
    @Test
    void activeAuthorizationDecryptsRealEnvelopeWithoutMutatingInstallation() throws Exception {
        List<String> before = facts();
        var target = directAuthorization(fixture.tenantId());
        assertThat(target).isPresent();
        assertThat(target.orElseThrow().provider()).isEqualTo("MOCK");
        assertThat(target.orElseThrow().plainToken().equals("mock:authorization-success")).isTrue();
        verify(cipher, times(1)).decrypt(any(AppPushToken.class), any(EncryptedPushToken.class));
        assertThat(facts()).isEqualTo(before);
    }

    /** 冻结/错tenant在任何密文解密之前拒绝，共用安装与原投递完整事实均保持。 */
    @ParameterizedTest
    @EnumSource(value = ProjectState.class, names = {"ARCHIVED", "DELETED", "WRONG_TENANT"})
    void invalidProjectReturnsEmptyBeforeDecryption(ProjectState state) throws Exception {
        applyState(state);
        List<String> before = facts();
        assertThat(directAuthorization(state == ProjectState.WRONG_TENANT ? Uuid7.generate() : fixture.tenantId())).isEmpty();
        assertNoDecryptionOrSend();
        assertThat(facts()).isEqualTo(before);
        assertInstallationUnchanged();
    }

    /** 未提供事务或只读事务都属于调用前置错误，adapter不得偷偷创建可写授权事务。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizationRejectsMissingOrReadOnlyTransaction(boolean readOnly) throws Exception {
        List<String> before = facts();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            Throwable failure;
            if (readOnly) {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setReadOnly(true);
                failure = catchThrowable(() -> transaction.execute(status -> authorize(fixture.tenantId())));
                assertThat(failure).isExactlyInstanceOf(IllegalStateException.class);
            } else {
                failure = catchThrowable(() -> authorize(fixture.tenantId()));
                assertThat(failure).isExactlyInstanceOf(IllegalTransactionStateException.class);
            }
        } finally { RlsScopeContext.clear(); }
        assertNoDecryptionOrSend();
        assertThat(facts()).isEqualTo(before);
    }

    /** 排队后冻结并非撤销投递事实；真实worker首尝试沿SKIPPED_AUTHORIZATION终态收束且不触达sender。 */
    @ParameterizedTest
    @EnumSource(value = ProjectState.class, names = {"ARCHIVED", "DELETED"})
    void executionSkipsFrozenQueuedDeliveryWithoutCallingSender(ProjectState state) throws Exception {
        Claimed claimed = claim(request);
        applyState(state);
        deliver(claimed);
        assertState("SKIPPED_AUTHORIZATION", 1, "AUTHORIZATION_REVOKED");
        assertNoDecryptionOrSend();
        assertInstallationUnchanged();
    }

    /** 删除先更新未提交，真实worker已进入SENDING后许可等待；提交删除后才确定跳过。 */
    @Test
    void deletionInFlightBlocksAuthorizationThenSkipsRealClaimedDelivery() throws Exception {
        Claimed claimed = claim(request);
        CompletableFuture<Integer> writerPid = capturePermitPid(null);
        CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> deleteTransaction(deletionPid, deleted, release));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> sending = executor.submit(() -> deliver(claimed));
            assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), deletionPid.get(3, TimeUnit.SECONDS), sending);
            assertState("SENDING", 1, null);
            release.countDown(); deletion.get(8, TimeUnit.SECONDS); sending.get(8, TimeUnit.SECONDS);
            assertState("SKIPPED_AUTHORIZATION", 1, "AUTHORIZATION_REVOKED");
            assertNoDecryptionOrSend(); assertInstallationUnchanged();
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 资格先取得则删除等待授权提交；进入真实sender时无事务，删除可完成，已获资格外发不承诺物理取消。 */
    @Test
    void authorizationLockEndsBeforeRealSenderAndDoesNotCancelAuthorizedSend() throws Exception {
        Claimed claimed = claim(request);
        CompletableFuture<Integer> authorizationPid = new CompletableFuture<>();
        CountDownLatch decrypted = new CountDownLatch(1);
        CountDownLatch releaseAuthorization = new CountDownLatch(1);
        CompletableFuture<Void> deletionCommitted = new CompletableFuture<>();
        PushTokenCipher cipherTarget = AopTestUtils.getUltimateTargetObject(cipher);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            authorizationPid.complete(appPid()); decrypted.countDown(); awaitRelease(releaseAuthorization);
            return result;
        }).when(cipherTarget).decrypt(argThat(token -> token != null && token.id().equals(pushTokenId)), any(EncryptedPushToken.class));
        DeterministicPushNotificationSender senderTarget = AopTestUtils.getUltimateTargetObject(sender);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            assertThat(TenantContext.current()).isEmpty();
            assertThat(RlsScopeContext.current()).isEmpty();
            deletionCommitted.get(5, TimeUnit.SECONDS);
            assertDeleted();
            return invocation.callRealMethod();
        }).when(senderTarget).send(argThat(delivery -> delivery != null && delivery.id().equals(request.deliveryId())), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sending = executor.submit(() -> deliver(claimed));
            assertThat(decrypted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> deletionPid = new CompletableFuture<>();
            Future<?> deletion = executor.submit(() -> {
                deleteTransaction(deletionPid, null, null); deletionCommitted.complete(null);
            });
            assertBlockedBy(deletionPid.get(3, TimeUnit.SECONDS), authorizationPid.get(3, TimeUnit.SECONDS), deletion);
            releaseAuthorization.countDown(); sending.get(10, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS);
            assertState("SUCCEEDED", 1, null);
            verify(sender, times(1)).send(any(), any()); assertInstallationUnchanged();
        } finally { releaseAuthorization.countDown(); shutdown(executor); }
    }

    /** adapter不吞57014、不假报空授权，原短事务回滚且未解密；解锁后相同身份可恢复授权。 */
    @Test
    void directAuthorizationPropagatesRealSqlTimeoutAndRecovers() throws Exception {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        List<String> before = facts();
        CompletableFuture<Integer> writerPid = capturePermitPid(null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<Throwable> attempt = executor.submit(() -> catchThrowable(() -> directAuthorization(fixture.tenantId())));
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), holderPid, attempt);
                assertThat(isSqlTimeout(attempt.get(10, TimeUnit.SECONDS))).isTrue();
                assertNoDecryptionOrSend(); assertThat(facts()).isEqualTo(before);
                holder.rollback();
                assertThat(directAuthorization(fixture.tenantId())).isPresent();
            } finally { holder.rollback(); shutdown(executor); }
        }
    }

    /** execution把基础设施故障记为INVALID_DELIVERY重试；只调整到期时间后沿真实重试Outbox和lease恢复第二尝试。 */
    @Test
    void executionSchedulesInfrastructureFailureThenRecoversThroughRealRetryAndClaim() throws Exception {
        Claimed first = claim(request);
        CompletableFuture<Throwable> sqlFailure = new CompletableFuture<>();
        CompletableFuture<Integer> writerPid = capturePermitPid(sqlFailure);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<?> sending = executor.submit(() -> deliver(first));
                assertBlockedBy(writerPid.get(3, TimeUnit.SECONDS), holderPid, sending);
                sending.get(12, TimeUnit.SECONDS);
                assertThat(isSqlTimeout(sqlFailure.get(2, TimeUnit.SECONDS))).isTrue();
                assertState("RETRY_SCHEDULED", 1, "INVALID_DELIVERY");
                assertNoDecryptionOrSend(); assertInstallationUnchanged();
                holder.rollback();
            } finally { holder.rollback(); shutdown(executor); }
        }
        makeOnlyRetryDueAfterVerifyingOriginalBudget();
        // 自动scheduler保持关闭；构造原类并注入全部真实依赖，只手动运行这一轮生产算法。
        NotificationRetryScheduler scheduler = new NotificationRetryScheduler(repository, outbox, transactions, mapper, clock, properties, lifecycle);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            scheduler.enqueueDueRetries();
        }
        NotificationDeliveryRequest retry = latestRequest();
        assertThat(retry.deliveryId()).isEqualTo(request.deliveryId());
        assertThat(retry.eventId()).isNotEqualTo(request.eventId());
        assertThat(retry.alarmEventId()).isEqualTo(request.alarmEventId());
        assertThat(retry.attemptNo()).isEqualTo(2);
        deliver(claim(retry));
        assertState("SUCCEEDED", 2, null);
        verify(sender, times(1)).send(any(), any()); assertInstallationUnchanged();
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND aggregate_id = ? AND event_type = 'ALARM_NOTIFICATION_DELIVERY_REQUEST'", fixture.projectId(), request.deliveryId())).isEqualTo(2);
        }
    }

    /** 独立adapter验收使用真实非只读TT；实际execution仍自己管理原授权事务。 */
    private Optional<AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget> directAuthorization(UUID tenantId) {
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try { return new TransactionTemplate(transactionManager).execute(status -> authorize(tenantId)); }
        finally { RlsScopeContext.clear(); }
    }

    /** 明确所有可信轴，不能从密文或项目外部输入重建身份。 */
    private Optional<AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget> authorize(UUID tenantId) {
        return authorization.authorize(tenantId, fixture.projectId(), fixture.deviceId(), fixture.userId(), pushTokenId);
    }

    /** 只从原持久Outbox解析当前event/attempt；函数领取必须随后证明对应唯一delivery。 */
    private NotificationDeliveryRequest latestRequest() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT o.payload::text FROM sys_outbox_event o JOIN alarm_notification_delivery d ON d.last_outbox_event_id = o.id WHERE d.project_id = ? AND d.channel = 'PUSH'")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                NotificationDeliveryRequest result = mapper.readValue(rows.getString(1), NotificationDeliveryRequest.class);
                assertThat(rows.next()).isFalse(); return result;
            }
        }
    }

    /** 专库唯一候选才允许真实全局领取；lease由仓储受控函数生成，不能手写或复用失效值。 */
    private Claimed claim(NotificationDeliveryRequest source) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE status = 'QUEUED'")).isEqualTo(1);
            // createDelivery未写dispatch_available_at，它由数据库DEFAULT now()生成；重排队亦保留此DB时刻。
            // created_at/JVM消息时间仅参与排序，不控制领取资格；按同一数据库时钟确认当前唯一候选已到期。
            assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE project_id = ? AND id = ? AND status = 'QUEUED' AND dispatch_available_at <= clock_timestamp() AND (dispatch_leased_until IS NULL OR dispatch_leased_until <= clock_timestamp())",
                    source.projectId(), source.deliveryId())).isEqualTo(1);
        }
        AlarmNotificationRepository.DispatchClaim claim;
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            claim = repository.claimDispatches(1, Duration.ofSeconds(30));
        }
        assertThat(claim.deliveries()).hasSize(1);
        var candidate = claim.deliveries().getFirst();
        assertThat(candidate.id()).isEqualTo(source.deliveryId());
        assertThat(candidate.eventId()).isEqualTo(source.eventId());
        assertThat(candidate.projectId()).isEqualTo(source.projectId());
        assertThat(candidate.tenantId()).isEqualTo(source.tenantId());
        assertThat(candidate.attemptNo()).isEqualTo(source.attemptNo());
        return new Claimed(source, claim.leaseToken());
    }

    /** 与真实worker相同，execution没有外层测试事务并走DATA池，完成后清空全部上下文。 */
    private void deliver(Claimed claimed) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            execution.deliverClaimed(claimed.request(), claimed.leaseToken());
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.current()).isEmpty();
            assertThat(RlsScopeContext.current()).isEmpty();
        }
    }

    /** 已真实产生退避状态后只改变该行next_attempt_at作为到期夹具，不改状态、attempt、lease或预算。 */
    private void makeOnlyRetryDueAfterVerifyingOriginalBudget() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT updated_at,next_attempt_at FROM alarm_notification_delivery WHERE project_id = ? AND id = ?")) {
            query.setObject(1, fixture.projectId()); query.setObject(2, request.deliveryId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                long delay = Duration.between(rows.getTimestamp(1).toInstant(), rows.getTimestamp(2).toInstant()).toMillis();
                long baseDelay = properties.firstRetryDelay().toMillis();
                assertThat(delay).isBetween(baseDelay, baseDelay + Math.max(1L, baseDelay * 15L / 100L));
            }
            execute(owner, "UPDATE alarm_notification_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE project_id = ? AND id = ? AND status = 'RETRY_SCHEDULED' AND attempt_count = 1 AND last_error_code = 'INVALID_DELIVERY'", fixture.projectId(), request.deliveryId());
        }
    }

    /** 终态和尝试数以真实数据库为准，基础设施重试绝不能被标为授权失效跳过。 */
    private void assertState(String state, int attempts, String error) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,attempt_count,last_error_code,provider_message_id,terminal_at FROM alarm_notification_delivery WHERE project_id = ?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(state);
                assertThat(rows.getInt(2)).isEqualTo(attempts); assertThat(rows.getString(3)).isEqualTo(error);
                if (state.equals("SUCCEEDED")) {
                    assertThat(rows.getString(4)).isEqualTo("mock-push-" + request.deliveryId());
                    assertThat(rows.getTimestamp(5)).isNotNull();
                } else if (state.equals("SKIPPED_AUTHORIZATION")) {
                    assertThat(rows.getString(4)).isNull(); assertThat(rows.getTimestamp(5)).isNotNull();
                } else { assertThat(rows.getString(4)).isNull(); assertThat(rows.getTimestamp(5)).isNull(); }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 确定拒绝与基础设施失败都不能解密或调用厂商；只验证受控方法，注册encrypt允许发生。 */
    private void assertNoDecryptionOrSend() {
        verify(cipher, never()).decrypt(any(AppPushToken.class), any(EncryptedPushToken.class));
        verify(sender, never()).send(any(), any());
    }

    /** 不因A生命周期拒绝撤销可供B使用的租户用户级安装。 */
    private void assertInstallationUnchanged() throws SQLException {
        assertThat(installationFacts()).isEqualTo(originalInstallation);
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", fixture.secondProjectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE project_id = ? AND app_user_id = ? AND status = 'ACTIVE'", fixture.secondProjectId(), fixture.userId())).isEqualTo(1);
        }
    }

    /** 信封全行比较包括密文、nonce、keyId与时间，避免只检查ACTIVE状态遗漏副作用。 */
    private List<String> installationFacts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT row_to_json(t)::text FROM app_push_token t WHERE tenant_id = ? AND app_user_id = ? ORDER BY id")) {
            query.setObject(1, fixture.tenantId()); query.setObject(2, fixture.userId());
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return List.copyOf(result);
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

    /** 真实许可SQL仍自行执行，超时仅旁观记录原cause供execution分类验收。 */
    private CompletableFuture<Integer> capturePermitPid(CompletableFuture<Throwable> failure) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            pid.complete(appPid());
            try { return invocation.callRealMethod(); }
            catch (RuntimeException exception) { if (failure != null) failure.complete(exception); throw exception; }
        }).when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
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
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { projectService.delete(fixture.projectId()); } finally { TenantContext.clear(); }
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

    /** 项目已冻结但用户、设备关系和安装仍保留，空受众不能归因于夹具消失。 */
    private void assertDeleted() throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_push_token WHERE tenant_id = ? AND app_user_id = ? AND status = 'ACTIVE'", fixture.tenantId(), fixture.userId())).isEqualTo(1);
        }
    }

    /** 比较本例完整业务行，证明只读授权及拒绝不改变原告警、意图和安装；不混入项目删除状态。 */
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

    /** 授权只允许当前ACTIVE，归档不保留新外发资格。 */
    private enum ProjectState {
        /** 项目只读。 */ ARCHIVED,
        /** OWNER已冻结。 */ DELETED,
        /** 显式tenant不匹配。 */ WRONG_TENANT
    }

    /** @param request 原持久信封 @param leaseToken 真实函数产生的当前有效领取租约 */
    private record Claimed(NotificationDeliveryRequest request, UUID leaseToken) { }

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
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, '共用安装项目B', 'sh-1', ?)", fixture.secondProjectId(), fixture.tenantId(), "push_auth_b_" + fixture.secondProjectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.secondProjectId(), fixture.accountId());
            execute(owner, "INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status) VALUES (?, ?, ?, ?, 'OBSERVER', 'ACTIVE')", Uuid7.generate(), fixture.tenantId(), fixture.secondProjectId(), fixture.userId());
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
            execute(owner, "DELETE FROM app_user_role WHERE project_id IN (?, ?)", fixture.projectId(), fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.secondProjectId());
            execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.secondProjectId());
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
        return DriverManager.getConnection(DATABASE_URL, AUTHORIZATION_POSTGRES.getUsername(), AUTHORIZATION_POSTGRES.getPassword());
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

    /** @param tenantId 租户 @param projectId 告警项目A @param accountId OWNER @param userId App用户 @param typeId 类型 @param deviceId 来源设备 @param ruleId 规则 @param groupId 路由组 @param templateId 模板 @param installationId 共用安装 @param messageId 激活source @param secondProjectId 同用户项目B */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID typeId, UUID deviceId,
                           UUID ruleId, UUID groupId, UUID templateId, UUID installationId, UUID messageId, UUID secondProjectId) { }
}
