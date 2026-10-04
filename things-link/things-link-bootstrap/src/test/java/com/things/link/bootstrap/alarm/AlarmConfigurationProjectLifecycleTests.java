package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.AlarmRuleCommand;
import com.things.link.alarm.application.AlarmRuleService;
import com.things.link.alarm.application.NotificationConfigurationService;
import com.things.link.alarm.domain.AlarmNotificationBinding;
import com.things.link.alarm.domain.AlarmNotificationGroup;
import com.things.link.alarm.domain.AlarmNotificationRecipient;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** S12-P0-5e5b：真实APP RLS下统一验收告警规则和通知配置的项目冻结边界。 */
@Import(AlarmConfigurationProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"ALARM_POSTGRES"})
class AlarmConfigurationProjectLifecycleTests extends AbstractIntegrationTest {

    /** 告警通知后台是全表领取，必须用物理专库隔离而非只换projectId。 */
    private static final String DATABASE_NAME = "alarm_config_lifecycle_"
            + UUID.randomUUID().toString().replace("-", "");
    /** PostgreSQL/Timescale版本沿用全仓真实验收镜像。 */
    private static final PostgreSQLContainer<?> ALARM_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring、Flyway和owner观察统一落在独占库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 独占库无需共享配额runner改动策略。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 本类只验配置，禁用通知发送领取者。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 任务扫描与告警配置正交。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 命令超时不得产生专库旁路写。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 属性回补不参与规则定义合法性。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;

    /** 规则CRUD保留真实设备属性校验、仓储和事务代理。 */
    @Autowired
    private AlarmRuleService rules;
    /** 通知组、收件人、模板和绑定保留真实关系校验与CAS。 */
    @Autowired
    private NotificationConfigurationService notifications;
    /** 角色读取始终调用真实项目服务，spy只建立确定竞态窗口。 */
    @MockitoSpyBean
    private ProjectService projects;
    /** 写许可始终调用真实生命周期服务，spy只在已持SHARE后暂停。 */
    @MockitoSpyBean
    private ProjectLifecycleAccessService lifecycle;
    /** APP连接用于真实事务PID和运行角色核验。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 默认角色读取不暂停。 */
    private Runnable afterRole = () -> { };
    /** 默认项目许可取得后不暂停。 */
    private Runnable afterPermit = () -> { };

    /** 每例校验专库/APP角色，并在真实方法返回后安装可控观察点。 */
    @BeforeEach
    void prepare() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        for (Object disabled : List.of(unusedQuotaRunner, unusedNotificationCoordinator, unusedTaskScanner,
                unusedCommandScanner, unusedBackfillScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
        ProjectService projectTarget = AopTestUtils.getUltimateTargetObject(projects);
        doAnswer(invocation -> {
            Object role = invocation.callRealMethod();
            afterRole.run();
            return role;
        }).when(projectTarget).requireRoleInProject(any());
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(lifecycleTarget).requireActiveForWrite(any(), any());
    }

    /** 跨租户ADMIN的调用tenant只代表行为主体，规则和通知配置必须写项目owner tenant。 */
    @ParameterizedTest
    @EnumSource(CreateKind.class)
    void crossTenantAdminCreatesWithPersistentProjectTenant(CreateKind kind) throws Exception {
        Fixture fixture = seed();

        Created created = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.collaboratorId(),
                () -> kind.create(rules, notifications, fixture));
        UUID persistedTenant = tenantOf(created.table(), created.id());

        assertSoftly(softly -> {
            softly.assertThat(created.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(persistedTenant).isEqualTo(fixture.ownerTenantId());
        });
    }

    /** ARCHIVED保留规则、组、模板、收件人与绑定读取，且所有读取都不能改变配置事实。 */
    @Test
    void archivedProjectKeepsExistingAlarmConfigurationReadableWithoutWrites() throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        AlarmRule rule = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> rules.get(fixture.projectId(), fixture.ruleId()));
        AlarmNotificationGroup group = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.getGroup(fixture.projectId(), fixture.groupId()));
        AlarmNotificationTemplate template = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.getTemplate(fixture.projectId(), fixture.templateId()));
        int recipients = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.recipients(fixture.projectId(), fixture.groupId()).size());
        int bindings = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.bindings(fixture.projectId(), fixture.ruleId()).size());
        int rulePage = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> rules.page(fixture.projectId(), null, 10).items().size());
        int groupPage = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.pageGroups(fixture.projectId(), null, 10).items().size());
        int templatePage = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.pageTemplates(fixture.projectId(), null, 10).items().size());
        // pageDeliveries需要完整告警实例/事件事实，已有AlarmLifecycleTests（S6）覆盖其只读分页；本类不伪造孤立投递。
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(rule.id()).isEqualTo(fixture.ruleId());
            softly.assertThat(group.id()).isEqualTo(fixture.groupId());
            softly.assertThat(template.id()).isEqualTo(fixture.templateId());
            softly.assertThat(recipients).isEqualTo(1);
            softly.assertThat(bindings).isEqualTo(1);
            softly.assertThat(rulePage).isEqualTo(1);
            softly.assertThat(groupPage).isEqualTo(2);
            softly.assertThat(templatePage).isEqualTo(2);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** 规则与四类通知配置的15个写入在归档后都必须50017且零写。 */
    @ParameterizedTest
    @EnumSource(ArchivedMutation.class)
    void archivedProjectRejectsRepresentativeCrudBeforeDomainMutation(ArchivedMutation mutation)
            throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        Throwable failure = catchThrowable(() -> asVoid(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> mutation.invoke(rules, notifications, fixture)));
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** 绑定写先取得SHARE后，归档真实等待其关系校验、INSERT和事务提交完成。 */
    @Test
    void bindingWritePermitKeepsArchiveBehindBusinessCommit() throws Exception {
        Fixture fixture = seed();
        CountDownLatch permitReached = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        afterPermit = () -> {
            writerPid.complete(actualPid());
            permitReached.countDown();
            await(releaseWriter);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AlarmNotificationBinding> writer = executor.submit(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> notifications.createBinding(fixture.projectId(), fixture.ruleId(), fixture.secondGroupId(),
                            fixture.secondTemplateId(), NotificationChannel.EMAIL, true)));
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
            AlarmNotificationBinding created = writer.get(5, TimeUnit.SECONDS);
            archiver.get(5, TimeUnit.SECONDS);
            String committedStatus = projectStatus(fixture);
            long bindingCount = count("alarm_notification_binding", fixture.projectId());

            assertSoftly(softly -> {
                softly.assertThat(created.ruleId()).isEqualTo(fixture.ruleId());
                softly.assertThat(created.groupId()).isEqualTo(fixture.secondGroupId());
                softly.assertThat(committedStatus).isEqualTo("ARCHIVED");
                softly.assertThat(bindingCount).isEqualTo(2);
            });
        } finally {
            releaseWriter.countDown();
            stop(executor);
        }
    }

    /** 归档先持项目锁时，通知配置写等待其提交，随后按50017拒绝且保持原快照。 */
    @Test
    void archiveLockFirstMakesWaitingNotificationWriteRejectReadOnly() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> notifications.createGroup(fixture.projectId(), "归档等待组", true))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                List<String> after = facts(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(50017);
                    softly.assertThat(after).isEqualTo(before);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 等待许可期间跨租户ADMIN降为VIEWER，锁后复核保留告警域40005而非改写为生命周期码。 */
    @Test
    void waitingAdminRechecksOriginalAlarmRoleAfterPermit() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.collaboratorId(),
                        () -> rules.create(fixture.projectId(), ruleCommand("等待降权规则", null, fixture)))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                execute(holder, "UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                        fixture.projectId(), fixture.collaboratorId());
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                List<String> after = facts(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(40005);
                    softly.assertThat(after).isEqualTo(before);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 项目锁等待由原五秒JDBC预算真实取消为57014，五表零变化；释放后同一模板入口恢复。 */
    @Test
    void projectLockTimeoutRollsBackAlarmConfigurationThenRecovers() throws Exception {
        assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        AtomicBoolean firstRole = new AtomicBoolean();
        afterRole = () -> {
            if (firstRole.compareAndSet(false, true)) {
                waitingPid.complete(actualPid());
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> notifications.createTemplate(fixture.projectId(), "超时模板",
                                NotificationChannel.EMAIL, "告警主题", "告警正文", true))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(12, TimeUnit.SECONDS);
                List<String> afterFailure = facts(fixture);
                assertSoftly(softly -> {
                    softly.assertThat(sqlState(failure)).isEqualTo("57014");
                    softly.assertThat(afterFailure).isEqualTo(before);
                });
                holder.rollback();
                AlarmNotificationTemplate recovered = as(
                        fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> notifications.createTemplate(fixture.projectId(), "超时模板",
                                NotificationChannel.EMAIL, "告警主题", "告警正文", true));
                assertSoftly(softly -> {
                    softly.assertThat(recovered.tenantId()).isEqualTo(fixture.ownerTenantId());
                    softly.assertThat(recovered.version()).isZero();
                });
                assertThat(count("alarm_notification_template", fixture.projectId())).isEqualTo(3);
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 绑定INSERT后的延迟23514必须回滚关系事实；移除故障可恢复，错误version仍保持原CAS冲突。 */
    @Test
    void deferredBindingFailureRollsBackThenRecoversWithoutWeakeningCas() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        try {
            try (Connection owner = owner()) {
                execute(owner, "CREATE FUNCTION alarm_config_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN IF NEW.project_id='" + fixture.projectId() + "'::uuid "
                        + "AND NEW.group_id='" + fixture.secondGroupId() + "'::uuid THEN "
                        + "RAISE EXCEPTION 'alarm config commit failure' USING ERRCODE='23514'; "
                        + "END IF; RETURN NEW; END $$");
                execute(owner, "CREATE CONSTRAINT TRIGGER alarm_config_commit_failure "
                        + "AFTER INSERT ON alarm_notification_binding DEFERRABLE INITIALLY DEFERRED "
                        + "FOR EACH ROW EXECUTE FUNCTION alarm_config_commit_failure()");
            }
            Throwable failure = catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(),
                    fixture.ownerId(), () -> notifications.createBinding(
                            fixture.projectId(), fixture.ruleId(), fixture.secondGroupId(),
                            fixture.secondTemplateId(), NotificationChannel.EMAIL, true)));
            List<String> afterFailure = facts(fixture);
            assertSoftly(softly -> {
                softly.assertThat(sqlState(failure)).isEqualTo("23514");
                softly.assertThat(afterFailure).isEqualTo(before);
            });
        } finally {
            try (Connection owner = owner()) {
                execute(owner, "DROP TRIGGER IF EXISTS alarm_config_commit_failure "
                        + "ON alarm_notification_binding");
                execute(owner, "DROP FUNCTION IF EXISTS alarm_config_commit_failure()");
            }
        }

        AlarmNotificationBinding recovered = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.createBinding(fixture.projectId(), fixture.ruleId(), fixture.secondGroupId(),
                        fixture.secondTemplateId(), NotificationChannel.EMAIL, true));
        assertThat(recovered.version()).isZero();
        List<String> afterRecovery = facts(fixture);
        Throwable stale = catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> notifications.updateBinding(fixture.projectId(), recovered.id(), fixture.groupId(),
                        fixture.templateId(), NotificationChannel.EMAIL, false, 1)));
        List<String> afterStale = facts(fixture);
        assertSoftly(softly -> {
            softly.assertThat(errorCode(stale)).isEqualTo(40008);
            softly.assertThat(afterStale).isEqualTo(afterRecovery);
            softly.assertThat(afterRecovery).hasSize(before.size() + 1);
        });
    }

    /** 每例建立真实跨租户ADMIN、数值上报属性、规则及两套可绑定通知配置。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate());
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '告警OWNER租户'), (?, '告警协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?, ?, '{noop}unused', '告警OWNER'), (?, ?, '{noop}unused', '告警ADMIN')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com",
                    fixture.collaboratorId(), fixture.collaboratorId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?),(?,?,?)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                            + "VALUES (?,?,'告警配置项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(),
                    "alarm_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'ADMIN')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,"
                            + "access_protocol,network_type,status) VALUES "
                            + "(?,?,?,'alarm_type','告警设备','DIRECT','STANDARD','WIFI','PUBLISHED')",
                    fixture.typeId(), fixture.ownerTenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,"
                            + "property_key,name,access_type,data_type,minimum_value,maximum_value) "
                            + "VALUES (?,?,?,?, 'temperature','温度','REPORT','NUMBER',-40,125)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) "
                            + "VALUES (?,?,?,?,'alarm_device','告警设备','OFFLINE')",
                    fixture.deviceId(), fixture.ownerTenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,"
                            + "property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) "
                            + "VALUES (?,?,?,'已有规则','HIGH_TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')",
                    fixture.ruleId(), fixture.ownerTenantId(), fixture.projectId(), fixture.deviceId());
            execute(owner, "INSERT INTO alarm_notification_group(id,tenant_id,project_id,name) VALUES "
                            + "(?,?,?,'主通知组'),(?,?,?,'备用通知组')",
                    fixture.groupId(), fixture.ownerTenantId(), fixture.projectId(),
                    fixture.secondGroupId(), fixture.ownerTenantId(), fixture.projectId());
            execute(owner, "INSERT INTO alarm_notification_recipient"
                            + "(id,tenant_id,project_id,group_id,channel,target) "
                            + "VALUES (?,?,?,?, 'EMAIL','owner@example.com')",
                    fixture.recipientId(), fixture.ownerTenantId(), fixture.projectId(), fixture.groupId());
            execute(owner, "INSERT INTO alarm_notification_template"
                            + "(id,tenant_id,project_id,name,channel,subject_template,body_template) VALUES "
                            + "(?,?,?,'主模板','EMAIL','告警','正文'),(?,?,?,'备用模板','EMAIL','告警','正文')",
                    fixture.templateId(), fixture.ownerTenantId(), fixture.projectId(),
                    fixture.secondTemplateId(), fixture.ownerTenantId(), fixture.projectId());
            execute(owner, "INSERT INTO alarm_notification_binding"
                            + "(id,tenant_id,project_id,rule_id,group_id,template_id,channel) "
                            + "VALUES (?,?,?,?,?,?,'EMAIL')",
                    fixture.bindingId(), fixture.ownerTenantId(), fixture.projectId(), fixture.ruleId(),
                    fixture.groupId(), fixture.templateId());
            owner.commit();
        }
        return fixture;
    }

    /** 合法规则命令复用真实设备数值属性，并按调用场景携带CAS version。 */
    private static AlarmRuleCommand ruleCommand(String name, Integer version, Fixture fixture) {
        return new AlarmRuleCommand(name, "HIGH_TEMPERATURE", fixture.deviceId(), "temperature",
                AlarmRule.ComparisonOperator.GT, 30, 0, AlarmRule.ComparisonOperator.LT, 25, 0,
                AlarmRule.Severity.MAJOR, true, version);
    }

    /** 独立提交ARCHIVED状态；业务角色仍保留以验收只读合同。 */
    private void archive(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
    }

    /** 五张配置表全行快照覆盖tenant、关系、CAS版本和软删除，不以数量代替原子性。 */
    private List<String> facts(Fixture fixture) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of("alarm_rule", "alarm_notification_group",
                    "alarm_notification_recipient", "alarm_notification_template",
                    "alarm_notification_binding")) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(r)::text FROM " + table
                                + " r WHERE project_id=? ORDER BY id")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            result.add(table + ':' + rows.getString(1));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 创建结果的持久tenant必须由owner连接读取，不能被错误RLS身份隐藏。 */
    private UUID tenantOf(String table, UUID id) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT tenant_id FROM " + table + " WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, id);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    /** 表名只来自测试内枚举常量；计数用于关系写成功后的补充断言。 */
    private long count(String table, UUID projectId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 当前业务线程必须是原APP写事务，返回其真实PID供锁图验证。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** owner夹具事务PID不得与APP业务PID混用。 */
    private static int backendId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    /** 使用数据库阻塞图和未授予锁共同取证，Future未完成本身不能证明锁顺序。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        try (Connection observer = owner(); PreparedStatement query = observer.prepareStatement(
                "SELECT ?=ANY(pg_blocking_pids(?)), "
                        + "EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
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
                    throw new AssertionError("操作完成但未观察到项目锁等待");
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到项目锁等待");
    }

    /** 屏障中断必须失败并恢复中断标记。 */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 结束并等待后台线程，禁止延迟写污染下个用例。 */
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 只接受异常链内真实SQLException状态。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /** 业务异常公开码；无异常或基础设施异常都返回空。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** 项目状态由owner读取已提交事实。 */
    private String projectStatus(Fixture fixture) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT status FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** 真实控制台范围调用后清空两个线程上下文。 */
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

    /** void入口复用同一身份边界。 */
    private static void asVoid(UUID tenantId, UUID projectId, UUID accountId, ThrowingAction action)
            throws Exception {
        as(tenantId, projectId, accountId, () -> {
            action.run();
            return null;
        });
    }

    /** owner只建夹具、生命周期状态与观察提交事实。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, ALARM_POSTGRES.getUsername(), ALARM_POSTGRES.getPassword());
    }

    /** 所有测试SQL都使用五秒语句预算。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 静态启动必须早于动态属性注册。 */
    private static String startDatabase() {
        ALARM_POSTGRES.start();
        return ALARM_POSTGRES.getJdbcUrl();
    }

    /** 独占容器由OwnedTestContainers在本类上下文物理关闭后回收；每例只需恢复挂钩并清理线程范围。 */
    @AfterEach
    void clearContext() {
        afterRole = () -> { };
        afterPermit = () -> { };
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 专库禁用所有无关全表领取，但保留规则/通知配置原服务与仓储。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 数据源和Flyway统一使用专库。 */
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

    /** 跨租户create分别覆盖规则与通知配置owner tenant来源。 */
    private enum CreateKind {
        /** 规则创建还保留设备属性公开端口校验。 */
        RULE {
            @Override
            Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                           Fixture fixture) {
                AlarmRule rule = rules.create(fixture.projectId(), ruleCommand("跨租户新规则", null, fixture));
                return new Created("alarm_rule", rule.id(), rule.tenantId());
            }
        },
        /** 通知组代表配置族create。 */
        GROUP {
            @Override
            Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                           Fixture fixture) {
                AlarmNotificationGroup group = notifications.createGroup(
                        fixture.projectId(), "跨租户通知组", true);
                return new Created("alarm_notification_group", group.id(), group.tenantId());
            }
        },
        /** 收件人create继续使用已有通知组关系。 */
        RECIPIENT {
            @Override
            Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                           Fixture fixture) {
                AlarmNotificationRecipient recipient = notifications.createRecipient(
                        fixture.projectId(), fixture.groupId(), NotificationChannel.EMAIL,
                        "cross-admin@example.com", true);
                return new Created("alarm_notification_recipient", recipient.id(), recipient.tenantId());
            }
        },
        /** 模板create覆盖固定变量与渠道校验后的持久owner tenant。 */
        TEMPLATE {
            @Override
            Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                           Fixture fixture) {
                AlarmNotificationTemplate template = notifications.createTemplate(
                        fixture.projectId(), "跨租户模板", NotificationChannel.EMAIL,
                        "告警主题", "告警正文 ${alarm.type}", true);
                return new Created("alarm_notification_template", template.id(), template.tenantId());
            }
        },
        /** 绑定create覆盖规则、组与模板三条真实关系。 */
        BINDING {
            @Override
            Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                           Fixture fixture) {
                AlarmNotificationBinding binding = notifications.createBinding(
                        fixture.projectId(), fixture.ruleId(), fixture.secondGroupId(),
                        fixture.secondTemplateId(), NotificationChannel.EMAIL, true);
                return new Created("alarm_notification_binding", binding.id(), binding.tenantId());
            }
        };

        /** @return 创建后的表名、ID和领域tenant */
        abstract Created create(AlarmRuleService rules, NotificationConfigurationService notifications,
                                Fixture fixture);
    }

    /** ARCHIVED参数覆盖两个服务各自create/update/delete代表入口。 */
    private enum ArchivedMutation {
        /** 规则新建。 */
        RULE_CREATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                rules.create(fixture.projectId(), ruleCommand("归档新规则", null, fixture));
            }
        },
        /** 规则CAS更新。 */
        RULE_UPDATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                rules.update(fixture.projectId(), fixture.ruleId(), ruleCommand("归档更新规则", 0, fixture));
            }
        },
        /** 规则软删除。 */
        RULE_DELETE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                rules.delete(fixture.projectId(), fixture.ruleId(), 0);
            }
        },
        /** 通知组新建。 */
        GROUP_CREATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.createGroup(fixture.projectId(), "归档新组", true);
            }
        },
        /** 通知组CAS更新。 */
        GROUP_UPDATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.updateGroup(fixture.projectId(), fixture.groupId(), "归档更新组", true, 0);
            }
        },
        /** 通知组软删除。 */
        GROUP_DELETE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.deleteGroup(fixture.projectId(), fixture.groupId(), 0);
            }
        },
        /** 收件人新建。 */
        RECIPIENT_CREATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.createRecipient(fixture.projectId(), fixture.groupId(), NotificationChannel.EMAIL,
                        "archived@example.com", true);
            }
        },
        /** 收件人CAS更新。 */
        RECIPIENT_UPDATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.updateRecipient(fixture.projectId(), fixture.recipientId(),
                        NotificationChannel.EMAIL, "updated@example.com", true, 0);
            }
        },
        /** 收件人软删除。 */
        RECIPIENT_DELETE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.deleteRecipient(fixture.projectId(), fixture.recipientId(), 0);
            }
        },
        /** 模板新建。 */
        TEMPLATE_CREATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.createTemplate(fixture.projectId(), "归档新模板", NotificationChannel.EMAIL,
                        "告警主题", "告警正文", true);
            }
        },
        /** 模板CAS更新。 */
        TEMPLATE_UPDATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.updateTemplate(fixture.projectId(), fixture.templateId(), "归档更新模板",
                        NotificationChannel.EMAIL, "告警主题", "告警正文", true, 0);
            }
        },
        /** 模板软删除。 */
        TEMPLATE_DELETE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.deleteTemplate(fixture.projectId(), fixture.templateId(), 0);
            }
        },
        /** 路由绑定新建。 */
        BINDING_CREATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.createBinding(fixture.projectId(), fixture.ruleId(), fixture.secondGroupId(),
                        fixture.secondTemplateId(), NotificationChannel.EMAIL, true);
            }
        },
        /** 路由绑定CAS更新。 */
        BINDING_UPDATE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.updateBinding(fixture.projectId(), fixture.bindingId(), fixture.secondGroupId(),
                        fixture.secondTemplateId(), NotificationChannel.EMAIL, true, 0);
            }
        },
        /** 路由绑定软删除。 */
        BINDING_DELETE {
            @Override void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                                  Fixture fixture) {
                notifications.deleteBinding(fixture.projectId(), fixture.bindingId(), 0);
            }
        };

        /** 调用原事务代理。 */
        abstract void invoke(AlarmRuleService rules, NotificationConfigurationService notifications,
                             Fixture fixture);
    }

    /** create返回的三项身份用于跨租户持久事实断言。 */
    private record Created(String table, UUID id, UUID tenantId) {
    }

    /** 所有ID都有真实父行；两套组/模板允许新增绑定不撞既有路由唯一键。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID projectId, UUID ownerId,
                           UUID collaboratorId, UUID typeId, UUID deviceId, UUID ruleId, UUID groupId,
                           UUID secondGroupId, UUID recipientId, UUID templateId, UUID secondTemplateId,
                           UUID bindingId) {
    }

    /** 允许void测试动作向外传播真实异常。 */
    @FunctionalInterface
    private interface ThrowingAction {
        /** 执行原业务入口。 */
        void run() throws Exception;
    }
}
