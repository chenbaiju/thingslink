package com.things.link.bootstrap.ingestion.property;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceReportedPropertiesCommitted;
import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.MessageLogService;
import com.things.link.telemetry.application.ProjectIngestionRejectedException;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.testing.AbstractIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * S12-P0-5e1c、ADR0065：标准遥测首次仲裁后取得原事务许可，重复消息保持原收束合同。
 * 独占真实PG与合法版本；使用物理六表及真实提交后派生观察，不用测试外层事务或模拟许可。
 */
@Import(PropertyIngestionProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"PROBE_POSTGRES"})
class PropertyIngestionProjectLifecycleTests extends AbstractIntegrationTest {
    /** 本夹具只验原事务；旧AFTER_COMMIT实时网络由独立真实Kafka用例验证，禁止误连开发Broker。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean(enforceOverride=true)
    private com.things.link.ingestion.application.RealtimeKafkaPublisher isolatedLegacyRealtimePublisher;
    /** 摘要只输出资格、异常类型、SQLSTATE和表计数，不打印设备载荷或账号数据。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyIngestionProjectLifecycleTests.class);
    /** D-109要求物理隔离；唯一project不足以隔离缓存上下文的全局领取者。 */
    private static final String DATABASE_NAME = "property_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 与共享验收同镜像同owner；独立容器保护全局角色及所有后台数据库对象。 */
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Flyway、CONTROL、DATA及owner观察必须共同使用专库地址。 */
    private static final String DATABASE_URL = startDatabase();
    /** 同一合法属性快照用于两版，差异仅为真实绑定资格，避免属性校验错误掩盖生命周期结果。 */
    private static final String SNAPSHOT = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\","
            + "\"accessType\":\"REPORT\",\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";
    /**
     * 完整行比较包含时间戳、revision和载荷，不能只凭数量相等声称未修改。
     * V20260808_1000把属性点同名对象变为显式app_current_project过滤视图，owner也不能绕过WHERE；
     * 取证读取真实internal物理表，其余五项均为原物理表（消息日志没有同名视图门面）。
     */
    private static final List<String> FACT_TABLES = List.of("sys_inbox_message", "ts_property_point_internal",
            "ts_device_message_log", "sys_message_log_inbox", "ts_property_aggregate_backfill", "dev_shadow");
    /** 父runner直接连共享库，专库探针必须抑制它。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 不允许自动领取通知；生产摄入和告警服务仍全部真实。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;
    /** 回补事实是观察对象，自动领取/刷新会污染完整行比较。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedAggregateBackfillScanner;
    /** 本片不验证任务后台；禁止无关调度自动访问专库。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskSchedulingScanner;
    /** 被测端口必须通过原Spring代理自行开启事务，测试外部没有TransactionTemplate。 */
    @MockitoSpyBean private PropertyIngestionService ingestion;
    /** 资格验证调用真实版本化设备端口，排除无效版本或错误身份造成假通过。 */
    @Autowired private DeviceIngestionService devices;
    /** UPGRADE必须通过原绑定事务追加不可变历史并CAS指针。 */
    @Autowired private ThingModelVersionBindingService bindings;
    /** 删除必须经过真实OWNER管理与原事务提交。 */
    @Autowired private ProjectService projects;
    /** spy只记录真实日额度异常，绝不替换返回值或绕过事务代理。 */
    @MockitoSpyBean private ProjectDailyQuotaDecisionService dailyQuota;
    /** 核对被测事务实际使用APP_ROLE与正确物理库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 明确检查Flyway连接而非仅检查继承配置的字符串。 */
    @Autowired private Environment environment;
    /** 每个展开用例均有独占身份，历史事实保留至容器回收。 */
    private Fixture fixture = newFixture();
    /** 捕获被外层catch吞掉的真实额度异常类型，使UnexpectedRollback仍有可核对来源。 */
    private final List<String> quotaObservations = new CopyOnWriteArrayList<>();
    /** 真实转换时间截断到PG精度，receivedAt使用该时间之后一秒且不超过十分钟。 */
    private Instant transitionAt;
    /** 原MANDATORY许可只在目标spy设置观察屏障，实际判断始终调用真实方法。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实删除预检PID不依赖新增遥测许可代码。 */
    @MockitoSpyBean private JdbcProjectRepository projectRepository;
    /** 在真实日志及日志inbox写入后注入内部故障，验证所有领域写共同回滚。 */
    @MockitoSpyBean private MessageLogService messageLogs;
    /** 使用真实Redis热影子端口，禁止通过查询服务回填掩盖提交回调缺失。 */
    @Autowired private DeviceCurrentValueCache valueCache;
    /** 只清理本类精确Redis键，不清共享缓存。 */
    @Autowired private StringRedisTemplate redis;
    /** legacy即时指标与提交后timer分别观察，不能以事务回滚撤销即时指标。 */
    @Autowired private MeterRegistry meters;
    /** 同步普通事件及AFTER_COMMIT监听器提供真实发布与提交边界。 */
    @Autowired private CommitObservation events;
    /** 每个额外项目都登记精确缓存清理身份，不删除不可变PG事实。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    /** 新增许可调用计数可区分首次拒绝与既有重放收束。 */
    private final AtomicInteger permitCalls = new AtomicInteger();
    /** 原inbox已插入、许可尚未查询时的测试观察点。 */
    private volatile Runnable beforePermit = () -> { };
    /** 真实许可已返回true后的观察点，绝不stub true。 */
    private volatile Runnable afterPermit = () -> { };
    /** 真实日志写入后的内部故障观察点。 */
    private volatile Runnable afterLog = () -> { };
    /** 第一摄入已暂停后才允许第二调用记录PID，不用线程名推定事务身份。 */
    private final AtomicBoolean secondIngestion = new AtomicBoolean();

    /** 每项均播种合法两版与真实升级，再为原代理后目标装配观察。 */
    @BeforeEach
    void prepare() throws Exception {
        seedFixture(false);
        installObservers();
    }

    /** 目标spy保留原代理事务；空观察钩子不会改变被测入口或额度返回值。 */
    private void installObservers() {
        assertThat(AopUtils.isAopProxy(lifecycle)).isTrue();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            permitCalls.incrementAndGet();
            actualAppPid();
            beforePermit.run();
            Object result = invocation.callRealMethod();
            if (Boolean.TRUE.equals(result)) afterPermit.run();
            return result;
        }).when(target).lockActiveForWrite(any(), any());
        assertThat(AopUtils.isAopProxy(dailyQuota)).isTrue();
        ProjectDailyQuotaDecisionService quotaTarget = AopTestUtils.getUltimateTargetObject(dailyQuota);
        assertThat(mockingDetails(quotaTarget).isSpy()).isTrue();
        doAnswer(invocation -> {
            actualAppPid();
            ProjectDailyQuotaDecisionService.Decision result =
                    (ProjectDailyQuotaDecisionService.Decision) invocation.callRealMethod();
            quotaObservations.add(invocation.getArgument(2) + "=" + result.status());
            return result;
        }).when(quotaTarget).decisionTrustedProject(any(), any(), any());
        MessageLogService logTarget = AopTestUtils.getUltimateTargetObject(messageLogs);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterLog.run();
            return result;
        }).when(logTarget).log(any());
    }

    /** 先验证真实落点再建种子；不调用既有PropertyIngestionTests的全库删除夹具。 */
    private void seedFixture(boolean initialOnly) throws Exception {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (Object disabled : List.of(unusedRestQuotaRelaxation, unusedNotificationWorkCoordinator,
                unusedAggregateBackfillScanner, unusedTaskSchedulingScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
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
            // 数据库目录实证观察对象为物理表，防止后续迁移再引入视图而静默把不可见行当零。
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                    query.setString(1, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).as("事实观察对象必须是物理表: %s", table).isIn("r", "p");
                    }
                }
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '遥测冻结探针租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '探针OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '遥测冻结探针', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "telemetry_probe_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'probe_type', '探针类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'probe_device', '探针设备', 'ONLINE')",
                    fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
        fixtures.add(fixture);
        // 基类版本种子通过下方override owner连接落专库；从不修改不可变版本或历史行。
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), fixture.typeId(), fixture.deviceId(), SNAPSHOT);
        transitionAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (initialOnly) return;
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, """
                    INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,
                        version_number,version_major,version_minor,version_patch,change_level,schema_profile,
                        model_snapshot,schema_digest,digest_algorithm)
                    VALUES (?, ?, ?, ?, '2.0.0',2,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                        encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                    """, fixture.versionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), SNAPSHOT, SNAPSHOT);
        }
        transitionAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        asOwner(() -> bindings.bind(fixture.projectId(), fixture.deviceId(), fixture.versionId(),
                Uuid7.generate(), TransitionType.UPGRADE, transitionAt));
    }

    /** ACTIVE两资格必须真实摄入；CURRENT同时证明缓存、普通事件、提交事件及timer观察有效。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void activeVersionEligibilityPreservesFactsAndCommitEffects(VersionCase version) throws Exception {
        Derived before = derived();
        assertActiveBaseline(version);
        assertThat(permitCalls).hasValue(1);
        Derived after = derived();
        if (version == VersionCase.CURRENT) {
            assertThat(after.cache()).hasSize(1);
            assertThat(after.cache().values().iterator().next().value().asDouble()).isEqualTo(18.5);
            assertThat(after.timer()).isEqualTo(before.timer() + 1);
            assertThat(after.committed()).hasSize(before.committed().size() + 1);
        } else assertThat(after).isEqualTo(before);
    }

    /** 首次明确拒绝先于额度与派生；原匹配重放false、payload/解析版本冲突30059不改成生命周期错误。 */
    @ParameterizedTest
    @CsvSource({"CURRENT,DELETE", "HISTORY_ONLY,DELETE", "CURRENT,ARCHIVED", "HISTORY_ONLY,ARCHIVED"})
    void committedFreezeRejectsNewMessageButPreservesReplayContract(VersionCase version, Freeze freeze) throws Exception {
        StandardUplinkMessage accepted = message(version, true);
        assertEligibility(accepted, version);
        assertThat(ingest(accepted)).isTrue();
        freeze(freeze);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        StandardUplinkMessage first = message(version, false);
        resetObservations();
        assertRejected(catchThrowable(() -> ingest(first)));
        assertThat(permitCalls).hasValue(1);
        assertNoQuotaOrEffects(before, effects, first.messageId());
        resetObservations();
        assertThat(ingest(accepted)).isFalse();
        assertThat(permitCalls).hasValue(0);
        assertThat(quotaObservations).isEmpty();
        assertThat(facts()).isEqualTo(before);
        assertThat(derived()).isEqualTo(effects);
        StandardUplinkMessage payloadConflict = copy(accepted, accepted.tenantId(), accepted.projectId(), accepted.deviceId(),
                accepted.modelVersion(), accepted.receivedAt(), Map.of("temperature", 99.0));
        assertBusinessCode(catchThrowable(() -> ingest(payloadConflict)), 30059);
        StandardUplinkMessage versionConflict = copy(accepted, accepted.tenantId(), accepted.projectId(), accepted.deviceId(),
                version == VersionCase.CURRENT ? "1.0.0" : "2.0.0", accepted.receivedAt(), accepted.payload());
        assertBusinessCode(catchThrowable(() -> ingest(versionConflict)), 30059);
        assertThat(permitCalls).hasValue(0);
        assertThat(facts()).isEqualTo(before);
    }

    /** 原inbox暂存已写但未许可时归档提交；许可必须重判并连inbox回滚，不能沿已成功额度推导。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void archiveBetweenInboxAndPermitRollsBackOriginalArbitration(VersionCase version) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        StandardUplinkMessage message = message(version, false);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        beforePermit = () -> { assertUncommittedInbox(message); pid.complete(actualAppPid()); await(release); };
        resetObservations();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Outcome> writer = executor.submit(() -> observed(message));
            pid.get(4, TimeUnit.SECONDS);
            assertThat(facts()).isEqualTo(before);
            freeze(Freeze.ARCHIVED);
            release.countDown();
            assertRejected(writer.get(5, TimeUnit.SECONDS).failure());
            assertNoQuotaOrEffects(before, effects, message.messageId());
        } finally { finish(executor, release); }
    }

    /** 归档未提交时真实SHARE等待；归档提交后RC重新判断，明确拒绝而不写额度/下游。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void inFlightArchiveMakesPermitWaitThenReject(VersionCase version) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        StandardUplinkMessage message = message(version, false);
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        beforePermit = () -> pid.complete(actualAppPid());
        resetObservations();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            Future<Outcome> writer = executor.submit(() -> observed(message));
            assertBlockedBy(pid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
            holder.commit();
            assertRejected(writer.get(5, TimeUnit.SECONDS).failure());
            assertNoQuotaOrEffects(before, effects, message.messageId());
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** SHARE在原摄入事务持续持有，非键归档和真实OWNER删除均需等待其提交；两资格副作用仍各自受限。 */
    @ParameterizedTest
    @CsvSource({"CURRENT,DELETE", "HISTORY_ONLY,DELETE", "CURRENT,ARCHIVED", "HISTORY_ONLY,ARCHIVED"})
    void writePermitBlocksFreezeUntilOriginalIngestionCommits(VersionCase version, Freeze freeze) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        StandardUplinkMessage message = message(version, false);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> freezePid = new CompletableFuture<>();
        afterPermit = () -> { assertUncommittedInbox(message); writerPid.complete(actualAppPid()); await(release); };
        if (freeze == Freeze.DELETE) observeDeletePid(freezePid);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> writer = executor.submit(() -> observed(message));
            int pid = writerPid.get(4, TimeUnit.SECONDS);
            Future<?> freezer = executor.submit(() -> runFreeze(freeze, freezePid));
            assertBlockedBy(freezePid.get(3, TimeUnit.SECONDS), pid, freezer);
            release.countDown();
            assertSuccess(writer.get(5, TimeUnit.SECONDS));
            freezer.get(5, TimeUnit.SECONDS);
            assertFrozen(freeze);
            assertSingleIngestion(version, message, before);
        } finally { finish(executor, release); }
    }

    /** inbox KEY SHARE后删除已经等待FOR UPDATE，原写者再申请SHARE仍须成功，明确验收此升级排序。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void inboxKeyShareCanUpgradeWhileRealDeleteAlreadyWaits(VersionCase version) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        StandardUplinkMessage message = message(version, false);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> deletePid = new CompletableFuture<>();
        AtomicBoolean permitGranted = new AtomicBoolean();
        beforePermit = () -> { assertUncommittedInbox(message); writerPid.complete(actualAppPid()); await(release); };
        afterPermit = () -> permitGranted.set(true);
        observeDeletePid(deletePid);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> writer = executor.submit(() -> observed(message));
            int pid = writerPid.get(4, TimeUnit.SECONDS);
            Future<?> deletion = executor.submit(() -> asOwner(() -> projects.delete(fixture.projectId())));
            assertBlockedBy(deletePid.get(3, TimeUnit.SECONDS), pid, deletion);
            assertThat(permitGranted).isFalse();
            release.countDown();
            assertSuccess(writer.get(5, TimeUnit.SECONDS));
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(permitGranted).isTrue();
            assertSingleIngestion(version, message, before);
            assertFrozen(Freeze.DELETE);
        } finally { finish(executor, release); }
    }

    /** 原五秒JDBC预算触发真实57014，不能包装为确定拒绝；锁回滚后相同message可再次首次处理。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void actualPermitTimeoutRollsBackAndSameMessageRecovers(VersionCase version) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        StandardUplinkMessage message = message(version, false);
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        beforePermit = () -> pid.complete(actualAppPid());
        resetObservations();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            long started = System.nanoTime();
            Future<Outcome> writer = executor.submit(() -> observed(message));
            assertBlockedBy(pid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
            Throwable failure = writer.get(8, TimeUnit.SECONDS).failure();
            assertThat(sqlState(failure)).isEqualTo("57014");
            assertThat(failure).isNotInstanceOf(ProjectIngestionRejectedException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(4000L, 8000L);
            assertNoQuotaOrEffects(before, effects, message.messageId());
            holder.rollback();
            beforePermit = () -> { };
            assertThat(ingest(message)).isTrue();
            assertSingleIngestion(version, message, before);
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** 原日志、日志inbox及前置时序/影子都实际写后故障，六表整体回滚且AFTER_COMMIT派生不发生。 */
    @ParameterizedTest
    @EnumSource(VersionCase.class)
    void downstreamFailureRollsBackAllFactsAndCommitEffectsThenRetries(VersionCase version) throws Exception {
        assertActiveBaseline(version);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        StandardUplinkMessage message = message(version, false);
        afterLog = () -> { assertActualBusinessRows(message); throw new InjectedFailure(); };
        assertThat(rootCause(catchThrowable(() -> ingest(message)))).isInstanceOf(InjectedFailure.class);
        assertThat(facts()).isEqualTo(before);
        assertThat(derived()).isEqualTo(effects);
        assertThat(events.published.contains(message.messageId())).isEqualTo(version == VersionCase.CURRENT);
        assertThat(events.committed).doesNotContain(message.messageId());
        afterLog = () -> { };
        assertThat(ingest(message)).isTrue();
        assertSingleIngestion(version, message, before);
    }

    /** 同message唯一仲裁在原事务真实等待：首写成功则另一方false；首写回滚则按当时项目资格接力。 */
    @ParameterizedTest
    @EnumSource(Arbitration.class)
    void concurrentSameMessagePreservesArbitrationAndRollbackHandoff(Arbitration arbitration) throws Exception {
        assertActiveBaseline(VersionCase.CURRENT);
        Map<String, List<String>> before = facts();
        Derived effects = derived();
        resetObservations();
        StandardUplinkMessage message = message(VersionCase.CURRENT, false);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> firstPid = new CompletableFuture<>();
        CompletableFuture<Integer> secondPid = new CompletableFuture<>();
        AtomicBoolean first = new AtomicBoolean(true);
        beforePermit = () -> {
            if (first.compareAndSet(true, false)) {
                firstPid.complete(actualAppPid()); await(release);
                if (arbitration != Arbitration.COMMIT) throw new InjectedFailure();
            }
        };
        // 第二次ingest原代理已创建事务，在目标方法开始取PID；之后设备验证与inbox仲裁仍原样执行。
        observeSecondIngestionPid(message, secondPid);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> initial = executor.submit(() -> observed(message));
            int holder = firstPid.get(4, TimeUnit.SECONDS);
            secondIngestion.set(true);
            Future<Outcome> contender = executor.submit(() -> observed(message));
            assertBlockedBy(secondPid.get(3, TimeUnit.SECONDS), holder, contender);
            if (arbitration == Arbitration.ROLLBACK_ARCHIVED) freeze(Freeze.ARCHIVED);
            release.countDown();
            Outcome one = initial.get(5, TimeUnit.SECONDS);
            Outcome two = contender.get(5, TimeUnit.SECONDS);
            if (arbitration == Arbitration.COMMIT) {
                assertSuccess(one); assertThat(two.failure()).isNull(); assertThat(two.returned()).isFalse();
                assertThat(permitCalls).hasValue(1);
                assertSingleIngestion(VersionCase.CURRENT, message, before);
            } else {
                assertThat(rootCause(one.failure())).isInstanceOf(InjectedFailure.class);
                assertThat(permitCalls).hasValue(2);
                if (arbitration == Arbitration.ROLLBACK_ACTIVE) { assertSuccess(two); assertSingleIngestion(VersionCase.CURRENT, message, before); }
                else { assertRejected(two.failure()); assertNoQuotaOrEffects(before, effects, message.messageId()); }
            }
        } finally { finish(executor, release); }
    }

    /** 原身份及版本前置失败不能申请许可或占inbox；不为统一错误码扩充跨项目可见性。 */
    @Test
    void invalidIdentityAndExpiredVersionFailBeforeInboxOrPermit() throws Exception {
        Fixture original = fixture;
        Instant originalTransition = transitionAt;
        fixture = newFixture(); seedFixture(false);
        Fixture foreign = fixture;
        fixture = original; transitionAt = originalTransition;
        resetObservations();
        Map<String, List<String>> before = facts();
        StandardUplinkMessage valid = message(VersionCase.CURRENT, false);
        List<StandardUplinkMessage> invalid = List.of(
                copy(valid, Uuid7.generate(), valid.projectId(), valid.deviceId(), valid.modelVersion(), valid.receivedAt(), valid.payload()),
                copy(valid, foreign.tenantId(), foreign.projectId(), valid.deviceId(), valid.modelVersion(), valid.receivedAt(), valid.payload()),
                copy(valid, valid.tenantId(), valid.projectId(), valid.deviceId(), "1.0.0", transitionAt.plusSeconds(601), valid.payload()),
                copy(valid, valid.tenantId(), valid.projectId(), valid.deviceId(), "9.9.9", valid.receivedAt(), valid.payload()));
        for (StandardUplinkMessage message : invalid) assertThat(catchThrowable(() -> ingest(message))).isInstanceOf(BusinessException.class);
        assertThat(permitCalls).hasValue(0);
        assertThat(quotaObservations).isEmpty();
        assertThat(facts()).isEqualTo(before);
        fixture = foreign;
        assertThat(facts().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
        fixture = original;
    }

    /** 一个项目冻结不阻断另一真实ACTIVE项目，沿各自设备/版本和owner二元组取得许可。 */
    @Test
    void anotherActiveProjectRemainsWritable() throws Exception {
        StandardUplinkMessage accepted = message(VersionCase.CURRENT, true);
        assertThat(ingest(accepted)).isTrue();
        Map<String, List<String>> originalFacts = facts();
        freeze(Freeze.DELETE);
        Fixture frozen = fixture;
        fixture = newFixture(); seedFixture(false); resetObservations();
        Map<String, List<String>> otherBefore = facts();
        StandardUplinkMessage collision = copy(accepted, fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                "2.0.0", transitionAt.plusSeconds(1), accepted.payload());
        Throwable failure = catchThrowable(() -> ingest(collision));
        // 原全局message唯一仲裁的RLS后查拒绝不扩成公共业务码；保留实际异常类型便于回归追踪。
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        LOGGER.info("PROPERTY_LIFECYCLE_CROSS_PROJECT_REPLAY outer={}", failureSummary(failure));
        assertThat(facts()).isEqualTo(otherBefore);
        assertThat(permitCalls).hasValue(0);
        assertThat(quotaObservations).isEmpty();
        resetObservations();
        assertActiveBaseline(VersionCase.CURRENT);
        assertThat(permitCalls).hasValue(1);
        fixture = frozen;
        assertThat(facts()).isEqualTo(originalFacts);
        assertFrozen(Freeze.DELETE);
    }

    /** 有效INITIAL省略版本先证明legacy与提交派生真实增长，再验证归档新消息不越过许可增加任何指标。 */
    @Test
    void deniedLegacyOmissionDoesNotEmitMetricsOrCurrentEffects() throws Exception {
        fixture = newFixture(); seedFixture(true); resetObservations();
        StandardUplinkMessage template = message(VersionCase.CURRENT, false);
        StandardUplinkMessage accepted = copy(template, template.tenantId(), template.projectId(), template.deviceId(), null, template.receivedAt(), template.payload());
        Derived before = derived();
        assertThat(ingest(accepted)).isTrue();
        Derived acceptedEffects = derived();
        assertThat(acceptedEffects.legacy()).isEqualTo(before.legacy() + 1);
        assertThat(acceptedEffects.timer()).isEqualTo(before.timer() + 1);
        assertThat(events.published).contains(accepted.messageId());
        assertThat(events.committed).contains(accepted.messageId());
        assertThat(acceptedEffects.cache()).hasSize(1);
        freeze(Freeze.ARCHIVED);
        Map<String, List<String>> facts = facts();
        resetObservations();
        StandardUplinkMessage nextTemplate = message(VersionCase.CURRENT, false);
        StandardUplinkMessage denied = copy(nextTemplate, nextTemplate.tenantId(), nextTemplate.projectId(), nextTemplate.deviceId(), null, nextTemplate.receivedAt(), nextTemplate.payload());
        assertRejected(catchThrowable(() -> ingest(denied)));
        assertNoQuotaOrEffects(facts, acceptedEffects, denied.messageId());
    }

    /** 资格来自生产解析器，特别钉住直接旧版十分钟窗口，不能把错误版本当作项目拒绝。 */
    private void assertEligibility(StandardUplinkMessage message, VersionCase version) {
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            DeviceIngestionContext context = devices.validateReportedProperties(
                    message.tenantId(), message.projectId(), message.deviceId(),
                    message.modelVersion(), message.receivedAt(), message.payload());
            assertThat(context.eligibility().name()).isEqualTo(version.name());
            assertThat(context.tenantId()).isEqualTo(message.tenantId());
        }
    }

    /** 只复位调用计数，不重置真实bean或许可屏障，便于区分baseline与本次业务。 */
    private void resetObservations() { permitCalls.set(0); quotaObservations.clear(); }

    /** 明确拒绝类型不能被原BusinessException、rollback-only副产物或SQL异常替代。 */
    private void assertRejected(Throwable failure) { assertThat(failure).isExactlyInstanceOf(ProjectIngestionRejectedException.class); }

    /** 原模型/重放业务错误保持既有错误码，不因项目冻结重新分类。 */
    private void assertBusinessCode(Throwable failure, int code) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(code);
    }

    /** 首次许可拒绝必须连inbox一起回滚；真实指标、缓存和提交监听都不改变且普通事件根本未发布。 */
    private void assertNoQuotaOrEffects(Map<String, List<String>> before, Derived effects, UUID messageId) throws SQLException {
        assertThat(quotaObservations).isEmpty();
        assertThat(facts()).isEqualTo(before);
        assertThat(derived()).isEqualTo(effects);
        assertThat(events.published).doesNotContain(messageId);
        assertThat(events.committed).doesNotContain(messageId);
    }

    /** 精确热键查询绕过DeviceCurrentValueService的数据库回填，能真实区分缓存回调有无执行。 */
    private Derived derived() {
        Map<DeviceCurrentValueCache.ValueKey, DeviceCurrentValue> cache = valueCache.findAll(fixture.projectId(),
                List.of(new DeviceCurrentValueCache.ValueKey(fixture.deviceId(), "temperature")));
        var timer = meters.find("thingslink.ingestion.uplink.end_to_end").timer();
        var legacy = meters.find("thingslink.ingestion.model_version.legacy_inferred").counter();
        return new Derived(Map.copyOf(cache), timer == null ? 0 : timer.count(), legacy == null ? 0 : legacy.count(),
                List.copyOf(events.committed));
    }

    /** 原inbox在同一APP事务可见，尚无该message的point/log，独立owner另查不可见确认没有提前提交。 */
    private void assertUncommittedInbox(StandardUplinkMessage message) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_inbox_message WHERE project_id=? AND message_id=?",
                Integer.class, message.projectId(), message.messageId())).isEqualTo(1);
        for (String table : List.of("ts_property_point", "ts_device_message_log", "sys_message_log_inbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=? AND message_id=?",
                    Integer.class, message.projectId(), message.messageId())).as(table).isZero();
        }
    }

    /** 下游故障只能在真实point、日志及日志inbox均存在后触发，不能用入口抛错冒充整体原子性。 */
    private void assertActualBusinessRows(StandardUplinkMessage message) {
        actualAppPid();
        for (String table : List.of("sys_inbox_message", "ts_property_point", "ts_device_message_log", "sys_message_log_inbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=? AND message_id=?",
                    Integer.class, message.projectId(), message.messageId())).as(table).isEqualTo(1);
        }
    }

    /** 四表精确message各新增一行且原行不变；近期新点不改回补，CURRENT影子更新而HISTORY保持不变。 */
    private void assertSingleIngestion(VersionCase version, StandardUplinkMessage message,
                                        Map<String, List<String>> before) throws SQLException {
        Map<String, List<String>> after = facts();
        for (String table : FACT_TABLES.subList(0, 4)) {
            assertThat(after.get(table)).as(table).hasSize(before.get(table).size() + 1).containsAll(before.get(table));
        }
        assertThat(after.get("ts_property_aggregate_backfill")).isEqualTo(before.get("ts_property_aggregate_backfill"));
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES.subList(0, 4)) {
                try (PreparedStatement query = owner.prepareStatement("SELECT count(*) FROM " + table + " WHERE project_id=? AND message_id=?")) {
                    query.setObject(1, message.projectId()); query.setObject(2, message.messageId());
                    try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).as(table).isEqualTo(1); }
                }
            }
            try (PreparedStatement query = owner.prepareStatement("SELECT model_version,value_double FROM ts_property_point_internal WHERE project_id=? AND message_id=?")) {
                query.setObject(1, message.projectId()); query.setObject(2, message.messageId());
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(message.modelVersion());
                    assertThat(rows.getDouble(2)).isEqualTo(26.5);
                }
            }
        }
        if (version == VersionCase.HISTORY_ONLY) {
            assertThat(after.get("dev_shadow")).isEqualTo(before.get("dev_shadow"));
            assertThat(events.published).doesNotContain(message.messageId());
            assertThat(events.committed).doesNotContain(message.messageId());
        } else {
            assertThat(after.get("dev_shadow")).hasSize(1).isNotEqualTo(before.get("dev_shadow"));
            assertThat(events.committed.stream().filter(message.messageId()::equals).count()).isEqualTo(1);
            assertThat(derived().cache().values()).singleElement().satisfies(value -> assertThat(value.value().asDouble()).isEqualTo(26.5));
        }
    }

    /** 真实删除findRole预检路径首次记录PID；不改变角色或为普通读取增设事务。 */
    private void observeDeletePid(CompletableFuture<Integer> pid) {
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(projectRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (!pid.isDone()) pid.complete(actualAppPid());
            return result;
        }).when(target).findRole(eq(fixture.projectId()), eq(fixture.accountId()));
    }

    /** 首写已暂停后进入的第二次真实ingest在方法体开始取PID，随后自行经过模型校验及INSERT等待。 */
    private void observeSecondIngestionPid(StandardUplinkMessage message, CompletableFuture<Integer> pid) {
        PropertyIngestionService target = AopTestUtils.getUltimateTargetObject(ingestion);
        doAnswer(invocation -> {
            if (secondIngestion.get()) pid.complete(actualAppPid());
            return invocation.callRealMethod();
        }).when(target).ingest(eq(message));
    }

    /** ARCHIVED是明确SQL状态夹具；真实DELETE始终走原服务，无测试外层事务。 */
    private void runFreeze(Freeze freeze, CompletableFuture<Integer> pid) {
        if (freeze == Freeze.DELETE) { asOwner(() -> projects.delete(fixture.projectId())); return; }
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            pid.complete(connectionPid(owner));
            try (PreparedStatement update = owner.prepareStatement("UPDATE sys_project SET status='ARCHIVED' WHERE id=?")) {
                update.setQueryTimeout(5); update.setObject(1, fixture.projectId());
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            owner.commit();
        } catch (SQLException failure) { throw new IllegalStateException("独立归档失败", failure); }
    }

    /** 从独立owner读取已提交冻结字段，不能从线程结束推测数据库状态。 */
    private void assertFrozen(Freeze freeze) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(freeze == Freeze.DELETE ? "DELETING" : "ARCHIVED");
                assertThat(rows.getBoolean(2)).isEqualTo(freeze == Freeze.DELETE);
            }
        }
    }

    /** 真实APP PID同时验证物理库、角色、非只读原事务，不能用owner或短事务替代业务写者。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 独立owner PID只作为归档/观察者身份，不冒充APP事务。 */
    private int connectionPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }

    /** pg_blocking_pids及未授予锁双证据，排除线程调度延迟造成的假等待。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT pg_backend_pid(), ?=ANY(pg_blocking_pids(?)), EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter); query.setQueryTimeout(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("业务已结束，未观察到指定PG锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定APP事务的真实阻塞关系");
    }

    /** 捕获原调用最外异常，等待完成后再与独立物理事实核对。 */
    private Outcome observed(StandardUplinkMessage message) {
        boolean[] value = {false};
        Throwable failure = catchThrowable(() -> value[0] = ingest(message));
        return new Outcome(value[0], failure);
    }

    /** 只有原业务明确true且无异常才是成功提交，false重复不能冒充首次写。 */
    private void assertSuccess(Outcome outcome) { assertThat(outcome.failure()).isNull(); assertThat(outcome.returned()).isTrue(); }

    /** 数据库异常保持原SQLSTATE；不读取错误文本来猜测确定失权。 */
    private String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** 仓储异常翻译可能包裹故障，检查根因而不强行要求最外层类型。 */
    private Throwable rootCause(Throwable failure) {
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /** 等待只用于受控边界，失败立即释放原事务，所有等待有上限。 */
    private void await(CountDownLatch release) {
        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    /** 失败也必须释放持锁线程；PG不可变历史保留至专库销毁，不关闭任何审计守卫。 */
    private void finish(ExecutorService executor, CountDownLatch release) throws InterruptedException {
        release.countDown(); executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 保留原message身份，仅精确改变本测试声明的字段，payload摘要仍由生产PG生成。 */
    private StandardUplinkMessage copy(StandardUplinkMessage source, UUID tenantId, UUID projectId, UUID deviceId,
                                       String version, Instant receivedAt, Map<String, Object> payload) {
        return new StandardUplinkMessage(source.messageId(), tenantId, projectId, deviceId, source.gatewayId(),
                source.protocol(), source.direction(), source.type(), version, source.occurredAt(), receivedAt,
                source.traceId(), source.rawBytes(), payload);
    }

    /** 额外项目只创建明确合法身份，从不按租户推定历史成员。 */
    private static Fixture newFixture() { return new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate()); }

    /** 记录原服务结果，Future仅负责并发调度。 */
    private record Outcome(boolean returned, Throwable failure) { }

    /** 真实提交后可见副作用快照，不含可在事务内先发布而最终回滚的普通事件。 */
    private record Derived(Map<DeviceCurrentValueCache.ValueKey, DeviceCurrentValue> cache,
                           long timer, double legacy, List<UUID> committed) { }

    /** 同message仲裁的三个有界结局，避免把首写回滚当幂等成功。 */
    private enum Arbitration {
        /** 首写提交后对手false。 */ COMMIT,
        /** 首写回滚，ACTIVE等待者接力首次写。 */ ROLLBACK_ACTIVE,
        /** 首写回滚且项目已归档，等待者首次许可拒绝。 */ ROLLBACK_ARCHIVED
    }

    /** 非业务内部异常只在指定真实写后触发，避免与Spring参数异常翻译混淆。 */
    private static final class InjectedFailure extends RuntimeException {
        /** 故障无载荷或账号信息。 */
        private InjectedFailure() { super("测试内部写后故障"); }
    }

    /** 普通事件与提交事件分别观察，确保失败事务不触发真正AFTER_COMMIT派生条件。 */
    static class CommitObservation {
        /** 精确message ID事件来源，避免其他上下文产生的事件计数干扰。 */
        private final List<UUID> published = new CopyOnWriteArrayList<>();
        /** 同步提交监听仅在真实原事务提交时写入。 */
        private final List<UUID> committed = new CopyOnWriteArrayList<>();
        /** 原publishEvent行为观察，不直接调用此方法制造事件。 */
        @EventListener
        public void published(DeviceReportedPropertiesCommitted event) { published.add(event.update().messageId()); }
        /** 无异步等待，原事务返回时已有明确提交资格证据；不声称已测真实Kafka送达。 */
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void committed(DeviceReportedPropertiesCommitted event) { committed.add(event.update().messageId()); }
    }

    /** 八日前合法点真实产生回补事实，冻结探针随后逐字段保护该非空基线。 */
    private void assertActiveBaseline(VersionCase version) throws Exception {
        StandardUplinkMessage message = message(version, true);
        DeviceIngestionContext context;
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            context = devices.validateReportedProperties(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    message.modelVersion(), message.receivedAt(), message.payload());
        }
        assertThat(context.eligibility().name()).isEqualTo(version.name());
        assertThat(context.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(ingest(message)).isTrue();
        Map<String, List<String>> committed = facts();
        for (String table : FACT_TABLES.subList(0, FACT_TABLES.size() - 1)) assertThat(committed.get(table)).as(table).hasSize(1);
        assertThat(committed.get("dev_shadow")).hasSize(version == VersionCase.CURRENT ? 1 : 0);
        assertThat(quotaObservations).hasSize(3).allSatisfy(value -> assertThat(value).endsWith("=NORMAL"));
        LOGGER.info("TELEMETRY_FREEZE_CONTROL version={} eligibility={} counts={} quota={}",
                version, context.eligibility(), counts(committed), quotaObservations);
    }

    /** 原数据面服务自行开启事务；不得用测试外层事务改变REQUIRED参与者的rollback-only结果。 */
    private boolean ingest(StandardUplinkMessage message) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return ingestion.ingest(message);
        }
    }

    /** receivedAt位于真实UPGRADE之后一秒；冻结探针使用新鲜点，避免七日前回补专属拒绝掩盖额度事务结果。 */
    private StandardUplinkMessage message(VersionCase version, boolean baseline) {
        Instant receivedAt = transitionAt.plusSeconds(1);
        return new StandardUplinkMessage(Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                version == VersionCase.CURRENT ? "2.0.0" : "1.0.0",
                baseline ? receivedAt.minus(8, ChronoUnit.DAYS) : receivedAt.minusSeconds(1),
                receivedAt, "telemetry-freeze-probe", 32, Map.of("temperature", baseline ? 18.5 : 26.5));
    }

    /** DELETE经过真实OWNER提交，ARCHIVED仅使用已有状态字段的明确SQL夹具，不编造归档API。 */
    private void freeze(Freeze freeze) throws SQLException {
        if (freeze == Freeze.DELETE) asOwner(() -> projects.delete(fixture.projectId()));
        else try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(freeze == Freeze.DELETE ? "DELETING" : "ARCHIVED");
                assertThat(rows.getBoolean(2)).isEqualTo(freeze == Freeze.DELETE);
            }
        }
    }

    /** 只有建模和删除以真实OWNER身份进入生产代理；摄入始终无控制台身份。 */
    private void asOwner(Runnable action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { action.run(); } finally { TenantContext.clear(); }
    }

    /** 独立owner读取所有原始字段，排序只服务稳定比较，不更改数据。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setObject(1, fixture.projectId());
                    List<String> values = new ArrayList<>();
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                    result.put(table, List.copyOf(values));
                }
            }
        }
        return result;
    }

    /** 日志只保留每张表的计数，失败断言仍比较完整行。 */
    private static Map<String, Integer> counts(Map<String, List<String>> facts) {
        Map<String, Integer> result = new LinkedHashMap<>();
        facts.forEach((table, rows) -> result.put(table, rows.size()));
        return result;
    }

    /** 异常链仅保留类名和SQLSTATE；UnexpectedRollback不携带原额度异常时由quotaObservations补齐。 */
    private static String failureSummary(Throwable failure) {
        if (failure == null) return "NONE";
        List<String> chain = new ArrayList<>();
        for (Throwable item = failure; item != null; item = item.getCause()) {
            chain.add(item.getClass().getSimpleName() + (item instanceof SQLException sql ? "[SQLSTATE=" + sql.getSQLState() + "]" : ""));
        }
        return String.join("->", chain);
    }

    /** Flyway及owner连接实际数据库、角色都必须匹配专库；仅URL字符串相同不足以证明。 */
    private void verifyOwnerIdentity(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
            assertThat(rows.getString(2)).isEqualTo(PROBE_POSTGRES.getUsername());
        }
    }

    /** 不删除不可变版本/转换历史或审计；所有专库身份和事实保留到容器回收。 */
    @AfterEach
    void cleanupContextOnly() {
        TenantContext.clear();
        for (Fixture value : fixtures) redis.delete("things-link:shadow:{" + value.projectId() + "}:" + value.deviceId() + ":temperature");
    }

    /** 覆盖基类版本夹具连接，杜绝在共享库插入设备版本。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, PROBE_POSTGRES.getUsername(), PROBE_POSTGRES.getPassword());
    }

    /** 参数化SQL仅作用于本例身份，固定表名不接收外部输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            // 包括主线程归档在内的夹具SQL也有界，未来许可位置改变不能令并发验收永久挂起。
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 容器先启动再提供Flyway属性，生命周期由本类OwnedTestContainers结束。 */
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }

    /** 使用Registrar覆盖父类DynamicPropertySource，保持现有镜像、用户、密码及池预算。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 测试事件观察保留原事务发布和真实AFTER_COMMIT条件，不启动Kafka外发验收。 */
        @Bean
        CommitObservation commitObservation() { return new CommitObservation(); }

        /** 禁止无关外发、重试和Kafka listener；直接摄入链保持真实生产装配。 */
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

    /** 两条真实版本资格不能被模型省略兼容逻辑合并。 */
    private enum VersionCase {
        /** 当前2.0.0可推进影子。 */ CURRENT,
        /** 直接旧版1.0.0只写历史。 */ HISTORY_ONLY
    }

    /** 两种冻结状态都保留设备、版本及历史事实。 */
    private enum Freeze {
        /** 真实OWNER删除提交。 */ DELETE,
        /** 明确归档状态夹具。 */ ARCHIVED
    }

    /** 独占项目及不可变版本身份；不从tenant推定任何历史成员。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId, UUID versionId) { }
}
