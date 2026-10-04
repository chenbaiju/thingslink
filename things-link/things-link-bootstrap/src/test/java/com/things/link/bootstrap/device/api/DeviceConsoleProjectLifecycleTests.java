package com.things.link.bootstrap.device.api;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceCredentialService;
import com.things.link.device.application.DeviceShadowService;
import com.things.link.device.domain.DeviceConnectionRepository;
import com.things.link.device.domain.DeviceCredential;
import com.things.link.device.domain.DeviceShadow;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * S12-P0-5e5a 旧基线：真实控制台身份、APP RLS和提交后回调共同约束设备凭据与影子。
 *
 * <p>测试期望表达冻结后的产品合同，因此在未取得持续项目许可的旧实现上刻意为RED；
 * owner连接只负责构造已提交生命周期事实与观察结果，不冒充控制台业务写。</p>
 */
@Import(DeviceConsoleProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"DEVICE_POSTGRES"})
class DeviceConsoleProjectLifecycleTests extends AbstractIntegrationTest {

    /** 全局后台领取不能与本类共享物理库，否则随机项目ID仍无法隔离全表候选。 */
    private static final String DATABASE_NAME = "device_console_lifecycle_"
            + UUID.randomUUID().toString().replace("-", "");
    /** 数据库版本与生产验收基线一致。 */
    private static final PostgreSQLContainer<?> DEVICE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring、Flyway和owner夹具必须落在同一个独占数据库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 独占库不需要共享配额runner改写全局策略。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 通知领取者与设备控制面合同无关。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 任务调度不得并发扫描专库。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 命令超时扫描不得产生旁路设备写。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 属性回补不得提前创建本类要观察的影子。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;

    /** 被测凭据服务保留真实Spring事务代理和真实JDBC仓储。 */
    @Autowired
    private DeviceCredentialService credentials;
    /** 被测影子服务保留真实Spring事务代理和真实JDBC仓储。 */
    @Autowired
    private DeviceShadowService shadows;
    /** 项目服务spy只在一个竞态点等待，角色查询本身始终调用真实实现。 */
    @MockitoSpyBean
    private ProjectService projects;
    /** 生命周期spy只在真实许可已经持锁后建立屏障，许可判断与事务边界仍调用生产实现。 */
    @MockitoSpyBean
    private ProjectLifecycleAccessService lifecycle;
    /** publish只会在事实事务COMMIT的afterCompletion中发生，用于观察真实提交后阶段。 */
    @MockitoSpyBean
    private CacheInvalidationPublisher invalidations;
    /** ADR0193：会话终止器在原事务内捕获活跃会话；空结果避免真实EMQX网络请求。 */
    @MockitoSpyBean
    private DeviceConnectionRepository connections;
    /** APP连接用于核验受限运行角色，不承担owner夹具职责。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 默认角色读取后立即继续；竞态测试临时替换该动作。 */
    private Runnable afterRole = () -> { };
    /** 默认许可取得后立即继续；写优先测试临时在持有SHARE时暂停原事务。 */
    private Runnable afterPermit = () -> { };

    /** 核验隔离和角色后，在真实角色返回点插入可控竞态挂钩。 */
    @BeforeEach
    void prepare() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        for (Object disabled : List.of(unusedQuotaRunner, unusedNotificationCoordinator, unusedTaskScanner,
                unusedCommandScanner, unusedBackfillScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
        ProjectService target = AopTestUtils.getUltimateTargetObject(projects);
        assertThat(target).isNotNull();
        doAnswer(invocation -> {
            Object role = invocation.callRealMethod();
            afterRole.run();
            return role;
        }).when(target).requireRoleInProject(any());
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(lifecycleTarget).requireActiveForWrite(any(), any());
    }

    /** ACTIVE写控制证明凭据落库、版本递增、原事务会话捕获和提交后缓存广播。 */
    @Test
    void activeCredentialRotationAndRevokeCommitFactsAndAfterCommitEffects() throws Exception {
        Fixture fixture = seed(true, true, false);
        resetEffects();

        DeviceCredential generated = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> credentials.generate(fixture.projectId(), fixture.deviceId()));
        assertThat(generated.plainSecret()).hasSize(64);
        assertThat(snapshot(fixture)).satisfies(state -> {
            assertThat(state.credentialRows()).isEqualTo(2);
            assertThat(state.activeCredentials()).isEqualTo(1);
            assertThat(state.credentialVersion()).isEqualTo(2);
        });
        assertEffects(fixture, 1);

        resetEffects();
        asVoid(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> credentials.revoke(fixture.projectId(), fixture.deviceId(), generated.id()));
        assertThat(snapshot(fixture)).satisfies(state -> {
            assertThat(state.credentialRows()).isEqualTo(2);
            assertThat(state.activeCredentials()).isZero();
            assertThat(state.credentialVersion()).isEqualTo(3);
        });
        assertEffects(fixture, 1);
    }

    /** ARCHIVED只读不允许generate/revoke改凭据事实，更不得触发任何提交后安全副作用。 */
    @ParameterizedTest
    @EnumSource(CredentialMutation.class)
    void archivedCredentialMutationMustRejectWithoutFactsOrAfterCommitEffects(CredentialMutation mutation)
            throws Exception {
        Fixture fixture = seed(true, true, false);
        archive(fixture);
        Snapshot before = snapshot(fixture);
        resetEffects();

        Throwable failure = catchThrowable(() -> asVoid(fixture.ownerTenantId(), fixture.projectId(),
                fixture.ownerId(), () -> mutation.invoke(credentials, fixture)));
        Snapshot after = snapshot(fixture);

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).as("生命周期错误码").isEqualTo(50017);
            softly.assertThat(after).as("凭据与设备版本快照").isEqualTo(before);
            softly.assertThat(effectCalls(invalidations, "publish")).as("提交后缓存发布").isZero();
            softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds"))
                    .as("原事务待踢会话捕获").isZero();
        });
    }

    /** ARCHIVED项目首次GET影子仍返回空视图，但不能把读操作物化成新的dev_shadow行。 */
    @Test
    void archivedMissingShadowReadMustRemainReadOnly() throws Exception {
        Fixture fixture = seed(true, false, false);
        archive(fixture);

        DeviceShadow shadow = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> shadows.get(fixture.projectId(), fixture.deviceId()));
        Snapshot after = snapshot(fixture);

        assertSoftly(softly -> {
            softly.assertThat(shadow.deviceId()).isEqualTo(fixture.deviceId());
            softly.assertThat(shadow.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(shadow.desired()).isNull();
            softly.assertThat(shadow.reported()).isNull();
            softly.assertThat(shadow.version()).isZero();
            softly.assertThat(after.shadowRows()).as("GET不得懒写").isZero();
        });
    }

    /** ARCHIVED项目的desired更新统一50017，原JSON与乐观锁版本必须保持不变。 */
    @Test
    void archivedDesiredUpdateMustRejectWithoutChangingShadow() throws Exception {
        Fixture fixture = seed(false, true, false);
        archive(fixture);
        Snapshot before = snapshot(fixture);

        Throwable failure = catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(),
                fixture.ownerId(), () -> shadows.updateDesired(
                        fixture.projectId(), fixture.deviceId(), "{\"new\":2}", 0)));
        Snapshot after = snapshot(fixture);

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** 跨租户ADMIN的行为tenant不是项目归属；新凭据必须持久化权威项目tenant。 */
    @Test
    void crossTenantAdminCredentialUsesProjectOwnerTenant() throws Exception {
        Fixture fixture = seed(false, false, true);
        resetEffects();

        DeviceCredential generated = as(fixture.collaboratorTenantId(), fixture.projectId(),
                fixture.collaboratorId(), () -> credentials.generate(fixture.projectId(), fixture.deviceId()));
        List<UUID> persistedTenants = credentialTenants(fixture);
        Snapshot after = snapshot(fixture);

        assertSoftly(softly -> {
            softly.assertThat(generated.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(persistedTenants).containsExactly(fixture.ownerTenantId());
            softly.assertThat(after.credentialVersion()).isEqualTo(2);
        });
        assertEffects(fixture, 1);
    }

    /** 角色校验后独立ARCHIVED已提交时，原业务事务必须复验并拒绝，不能穿透写与回调。 */
    @Test
    void archiveCommittedAfterRoleCheckMustStopCredentialWrite() throws Exception {
        Fixture fixture = seed(true, false, false);
        Snapshot before = snapshot(fixture);
        AtomicBoolean once = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        afterRole = () -> {
            if (!once.compareAndSet(false, true)) {
                return;
            }
            Future<?> archived = executor.submit(() -> {
                try {
                    archive(fixture);
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            try {
                archived.get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        resetEffects();
        try {
            Throwable failure = catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(),
                    fixture.ownerId(), () -> credentials.generate(fixture.projectId(), fixture.deviceId())));
            String committedStatus = projectStatus(fixture);
            Snapshot after = snapshot(fixture);
            assertSoftly(softly -> {
                softly.assertThat(once.get()).isTrue();
                softly.assertThat(committedStatus).isEqualTo("ARCHIVED");
                softly.assertThat(errorCode(failure)).isEqualTo(50017);
                softly.assertThat(after).isEqualTo(before);
                softly.assertThat(effectCalls(invalidations, "publish")).isZero();
                softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isZero();
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** ACTIVE与ARCHIVED均保留纯读：已有凭据不泄露明文，已有影子不发生隐式改写。 */
    @ParameterizedTest
    @EnumSource(ReadState.class)
    void existingCredentialAndShadowReadsHaveNoSideEffects(ReadState state) throws Exception {
        Fixture fixture = seed(true, true, false);
        if (state == ReadState.ARCHIVED) {
            archive(fixture);
        }
        Snapshot before = snapshot(fixture);
        resetEffects();

        List<DeviceCredential> listed = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> credentials.list(fixture.projectId(), fixture.deviceId()));
        DeviceShadow shadow = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> shadows.get(fixture.projectId(), fixture.deviceId()));
        Snapshot after = snapshot(fixture);

        assertSoftly(softly -> {
            softly.assertThat(listed).singleElement().satisfies(credential -> {
                softly.assertThat(credential.id()).isEqualTo(fixture.credentialId());
                softly.assertThat(credential.plainSecret()).isNull();
            });
            softly.assertThat(shadow.desired()).isEqualTo("{\"old\": 1}");
            softly.assertThat(after).isEqualTo(before);
            softly.assertThat(effectCalls(invalidations, "publish")).isZero();
            softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isZero();
        });
    }

    /** 写事务先取得项目SHARE时，归档必须真实等待到凭据事实及提交后回调完成。 */
    @Test
    void grantedCredentialWriteKeepsArchiveBehindBusinessCommit() throws Exception {
        Fixture fixture = seed(true, false, false);
        CountDownLatch permitReached = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        afterPermit = () -> {
            writerPid.complete(actualPid());
            permitReached.countDown();
            await(releaseWriter);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        resetEffects();
        try {
            Future<DeviceCredential> writer = executor.submit(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> credentials.generate(fixture.projectId(), fixture.deviceId())));
            assertThat(permitReached.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> archivePid = new CompletableFuture<>();
            Future<?> archiver = executor.submit(() -> {
                try (Connection owner = owner()) {
                    archivePid.complete(backendId(owner));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                } catch (SQLException exception) {
                    archivePid.completeExceptionally(exception);
                    throw new IllegalStateException(exception);
                }
            });
            assertBlockedBy(archivePid.get(5, TimeUnit.SECONDS), writerPid.get(5, TimeUnit.SECONDS), archiver);
            releaseWriter.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS).plainSecret()).hasSize(64);
            archiver.get(5, TimeUnit.SECONDS);
            Snapshot after = snapshot(fixture);
            String committedStatus = projectStatus(fixture);
            assertSoftly(softly -> {
                softly.assertThat(committedStatus).isEqualTo("ARCHIVED");
                softly.assertThat(after.credentialRows()).isEqualTo(2);
                softly.assertThat(after.activeCredentials()).isEqualTo(1);
                softly.assertThat(after.credentialVersion()).isEqualTo(2);
            });
            assertEffects(fixture, 1);
        } finally {
            releaseWriter.countDown();
            stop(executor);
        }
    }

    /** 归档事务先持项目行锁时，凭据写必须等待其提交后按50017拒绝且保持原事实。 */
    @Test
    void archiveLockFirstMakesWaitingCredentialWriteRejectReadOnly() throws Exception {
        Fixture fixture = seed(true, false, false);
        Snapshot before = snapshot(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        resetEffects();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> credentials.generate(fixture.projectId(), fixture.deviceId()))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                Snapshot after = snapshot(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(50017);
                    softly.assertThat(after).isEqualTo(before);
                    softly.assertThat(effectCalls(invalidations, "publish")).isZero();
                    softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isZero();
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 等待项目许可期间ADMIN被降为VIEWER，锁后角色复核必须保持设备域30024语义。 */
    @Test
    void waitingCrossTenantAdminRechecksRoleAfterProjectPermit() throws Exception {
        Fixture fixture = seed(true, false, true);
        Snapshot before = snapshot(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        resetEffects();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.collaboratorId(),
                        () -> credentials.generate(fixture.projectId(), fixture.deviceId()))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                execute(holder, "UPDATE sys_project_member SET role='VIEWER' "
                        + "WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                Snapshot after = snapshot(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(30024);
                    softly.assertThat(after).isEqualTo(before);
                    softly.assertThat(effectCalls(invalidations, "publish")).isZero();
                    softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isZero();
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 延迟23514发生在提交点，凭据/版本回滚且不广播缓存；已执行的只读会话捕获不属于外部副作用。 */
    @Test
    void deferredCommitFailureRollsBackCredentialRotationAndRecovers() throws Exception {
        Fixture fixture = seed(true, false, false);
        Snapshot before = snapshot(fixture);
        try (Connection owner = owner()) {
            execute(owner, "CREATE FUNCTION device_credential_commit_failure() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='" + fixture.deviceId()
                    + "'::uuid AND NEW.credential_version<>OLD.credential_version THEN "
                    + "RAISE EXCEPTION 'device credential commit failure' USING ERRCODE='23514'; "
                    + "END IF; RETURN NEW; END $$");
            execute(owner, "CREATE CONSTRAINT TRIGGER device_credential_commit_failure "
                    + "AFTER UPDATE ON dev_device DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
                    + "EXECUTE FUNCTION device_credential_commit_failure()");
        }
        resetEffects();
        try {
            Throwable failure = catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(),
                    fixture.ownerId(), () -> credentials.generate(fixture.projectId(), fixture.deviceId())));
            Snapshot after = snapshot(fixture);
            assertSoftly(softly -> {
                softly.assertThat(sqlState(failure)).isEqualTo("23514");
                softly.assertThat(after).isEqualTo(before);
                softly.assertThat(effectCalls(invalidations, "publish")).isZero();
                softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isEqualTo(1);
            });
        } finally {
            try (Connection owner = owner()) {
                execute(owner, "DROP TRIGGER IF EXISTS device_credential_commit_failure ON dev_device");
                execute(owner, "DROP FUNCTION IF EXISTS device_credential_commit_failure()");
            }
        }
        resetEffects();
        DeviceCredential recovered = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> credentials.generate(fixture.projectId(), fixture.deviceId()));
        assertThat(recovered.plainSecret()).hasSize(64);
        assertThat(snapshot(fixture)).satisfies(after -> {
            assertThat(after.credentialRows()).isEqualTo(2);
            assertThat(after.activeCredentials()).isEqualTo(1);
            assertThat(after.credentialVersion()).isEqualTo(2);
        });
        assertEffects(fixture, 1);
    }

    /** 原五秒JDBC预算真实取消项目锁等待，57014不能转业务码；解锁后同一操作恢复成功。 */
    @Test
    void projectLockTimeoutLeavesCredentialFactsUntouchedThenRecovers() throws Exception {
        assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
        Fixture fixture = seed(true, false, false);
        Snapshot before = snapshot(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        resetEffects();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> credentials.generate(fixture.projectId(), fixture.deviceId()))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(12, TimeUnit.SECONDS);
                Snapshot after = snapshot(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(sqlState(failure)).isEqualTo("57014");
                    softly.assertThat(after).isEqualTo(before);
                    softly.assertThat(effectCalls(invalidations, "publish")).isZero();
                    softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isZero();
                });
                holder.rollback();
                resetEffects();
                DeviceCredential recovered = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> credentials.generate(fixture.projectId(), fixture.deviceId()));
                assertThat(recovered.plainSecret()).hasSize(64);
                assertThat(snapshot(fixture).credentialVersion()).isEqualTo(2);
                assertEffects(fixture, 1);
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 当前调用必须位于原APP写事务内；返回PID用于证明项目锁的实际持有者。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** owner连接PID只能用于夹具事务锁图，不能冒充生产APP事务。 */
    private static int backendId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    /** 轮询真实阻塞图；Future未完成本身不是持锁证据。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        try (Connection observer = owner(); PreparedStatement query = observer.prepareStatement(
                "SELECT ? = ANY(pg_blocking_pids(?)), "
                        + "EXISTS (SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            query.setInt(1, holder);
            query.setInt(2, waiter);
            query.setInt(3, waiter);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    if (rows.getBoolean(1) && rows.getBoolean(2)) {
                        return;
                    }
                }
                if (operation.isDone()) {
                    throw new AssertionError("操作已完成但没有观察到预期项目锁等待");
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到预期项目锁等待");
    }

    /** 测试屏障超时或中断都必须使后台业务失败，不能继续污染后续用例。 */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 关闭前先中断并等待线程退出，防止独占库内残留后写。 */
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** SQLSTATE沿异常链提取，不能把任意RuntimeException归因成预期数据库失败。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /** 每例建立完整项目成员、真实设备及可选凭据/影子，跨租户协作者仍使用自己的tenant。 */
    private Fixture seed(boolean credential, boolean shadow, boolean collaborator) throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '设备OWNER租户'), (?, '设备协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?, ?, '{noop}unused', '设备OWNER'), (?, ?, '{noop}unused', '设备ADMIN')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com",
                    fixture.collaboratorId(), fixture.collaboratorId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?),(?,?,?)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                            + "VALUES (?,?,'设备控制面项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(),
                    "device_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'ADMIN')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,status) "
                            + "VALUES (?,?,?,'device_console','控制面设备','OFFLINE')",
                    fixture.deviceId(), fixture.ownerTenantId(), fixture.projectId());
            if (credential) {
                execute(owner, "INSERT INTO dev_credential"
                                + "(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) "
                                + "VALUES (?,?,?,?, 'ACCESS_TOKEN', ?, '旧凭据')",
                        fixture.credentialId(), fixture.ownerTenantId(), fixture.projectId(), fixture.deviceId(),
                        "0".repeat(64));
            }
            if (shadow) {
                execute(owner, "INSERT INTO dev_shadow(device_id,tenant_id,project_id,desired,version) "
                                + "VALUES (?,?,?, '{\"old\":1}'::jsonb, 0)",
                        fixture.deviceId(), fixture.ownerTenantId(), fixture.projectId());
            }
            owner.commit();
        }
        return fixture;
    }

    /** 独立提交ARCHIVED事实；旧基线没有许可锁时它能在角色返回后抢先完成。 */
    private void archive(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
    }

    /** 凭据和影子所有可变事实由owner统一观察，避免RLS把错误tenant行隐藏成不存在。 */
    private Snapshot snapshot(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            return new Snapshot(
                    number(owner, "SELECT count(*) FROM dev_credential WHERE project_id=?", fixture.projectId()),
                    number(owner, "SELECT count(*) FROM dev_credential WHERE project_id=? AND deleted_at IS NULL",
                            fixture.projectId()),
                    number(owner, "SELECT credential_version FROM dev_device WHERE id=?", fixture.deviceId()),
                    number(owner, "SELECT count(*) FROM dev_shadow WHERE project_id=?", fixture.projectId()),
                    text(owner, "SELECT desired::text FROM dev_shadow WHERE project_id=?", fixture.projectId()),
                    nullableNumber(owner, "SELECT version FROM dev_shadow WHERE project_id=?", fixture.projectId()));
        }
    }

    /** 返回全部凭据tenant，直接揭示跨租户协作者把行为tenant误写入项目事实的问题。 */
    private List<UUID> credentialTenants(Fixture fixture) throws SQLException {
        try (Connection owner = owner();
             PreparedStatement query = owner.prepareStatement(
                     "SELECT tenant_id FROM dev_credential WHERE project_id=? ORDER BY created_at,id")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getObject(1, UUID.class));
                }
                return result;
            }
        }
    }

    /** 项目状态必须来自owner观察，业务RLS不会参与断言。 */
    private String projectStatus(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            return text(owner, "SELECT status FROM sys_project WHERE id=?", fixture.projectId());
        }
    }

    /** 清空此前夹具或本地缓存活动的spy记录，只计当前业务调用的提交后行为。 */
    private void resetEffects() {
        clearInvocations(invalidations, connections);
    }

    /** 分别检查提交后缓存广播及原事务会话捕获；捕获不是Broker外部断开证据。 */
    private void assertEffects(Fixture fixture, long expected) {
        assertSoftly(softly -> {
            softly.assertThat(effectCalls(invalidations, "publish")).isEqualTo(expected);
            softly.assertThat(effectCalls(connections, "findActiveMqttSessionIds")).isEqualTo(expected);
            if (expected > 0) {
                softly.assertThat(mockingDetails(invalidations).getInvocations()).anySatisfy(invocation ->
                        softly.assertThat(invocation.getMethod().getName()).isEqualTo("publish"));
            }
        });
    }

    /** Mockito调用历史用于SoftAssertions一次展示持久事实与副作用穿透，避免首个断言遮住其余证据。 */
    private static long effectCalls(Object spy, String method) {
        return mockingDetails(spy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method))
                .count();
    }

    /** 业务异常按稳定公开码比较；无异常或基础设施异常都不能伪装成正确生命周期拒绝。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** 在真实控制台范围执行有返回值调用，退出后必须清空线程身份。 */
    private static <T> T as(UUID tenantId, UUID projectId, UUID accountId,
                            java.util.concurrent.Callable<T> action) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            return action.call();
        } finally {
            TenantContext.clear();
            RlsScopeContext.clear();
        }
    }

    /** void调用沿用同一真实身份边界。 */
    private static void asVoid(UUID tenantId, UUID projectId, UUID accountId, ThrowingAction action)
            throws Exception {
        as(tenantId, projectId, accountId, () -> {
            action.run();
            return null;
        });
    }

    /** owner连接仅建夹具、提交生命周期与读取提交后的完整事实。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, DEVICE_POSTGRES.getUsername(), DEVICE_POSTGRES.getPassword());
    }

    /** 所有夹具SQL都有五秒预算，基础设施阻塞不能把CI无限挂住。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 单值数字查询缺行必须失败，不能把无事实错误解释成零。 */
    private static long number(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 可空整数用于缺影子控制，缺行与version=0不可混为一谈。 */
    private static Integer nullableNumber(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : null;
            }
        }
    }

    /** 可空文本保留“缺影子”与“存在但desired为空”的区别。 */
    private static String text(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** 静态启动早于动态属性注册，避免Spring/Flyway读到未映射端口。 */
    private static String startDatabase() {
        DEVICE_POSTGRES.start();
        return DEVICE_POSTGRES.getJdbcUrl();
    }

    /** 不清理不可变业务证据；独占容器由本类OwnedTestContainers回收，只清线程上下文与竞态挂钩。 */
    @AfterEach
    void clearContext() {
        afterRole = () -> { };
        afterPermit = () -> { };
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 独占数据库关闭所有无关后台领取，保留原设备服务、仓储、事务和Redis发布器。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Spring运行连接与Flyway owner连接指向同一个专库。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** 凭据两种公开写入口必须共享同一生命周期边界。 */
    private enum CredentialMutation {
        /** 轮换会先撤销旧凭据再新增。 */
        GENERATE {
            @Override
            void invoke(DeviceCredentialService service, Fixture fixture) {
                service.generate(fixture.projectId(), fixture.deviceId());
            }
        },
        /** 显式撤销直接软删当前凭据。 */
        REVOKE {
            @Override
            void invoke(DeviceCredentialService service, Fixture fixture) {
                service.revoke(fixture.projectId(), fixture.deviceId(), fixture.credentialId());
            }
        };

        /** @param service 原事务代理 @param fixture 真实凭据夹具 */
        abstract void invoke(DeviceCredentialService service, Fixture fixture);
    }

    /** 读取合同同时覆盖ACTIVE和只读ARCHIVED。 */
    private enum ReadState {
        /** 正常项目。 */ ACTIVE,
        /** 已归档只读项目。 */ ARCHIVED
    }

    /** 需要检查的全部可变持久事实。 */
    private record Snapshot(long credentialRows, long activeCredentials, long credentialVersion,
                            long shadowRows, String desired, Integer shadowVersion) {
    }

    /** 每例身份均有真实祖先；credentialId在未建凭据场景只是稳定预留ID。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID projectId, UUID ownerId,
                           UUID collaboratorId, UUID deviceId, UUID credentialId) {
    }

    /** 允许void测试动作向外抛出真实业务或SQL异常。 */
    @FunctionalInterface
    private interface ThrowingAction {
        /** 执行原业务调用。 */
        void run() throws Exception;
    }
}
