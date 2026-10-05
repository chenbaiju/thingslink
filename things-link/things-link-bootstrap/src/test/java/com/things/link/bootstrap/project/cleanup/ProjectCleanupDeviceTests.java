package com.things.link.bootstrap.project.cleanup;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.support.audit.AuditLogService;
import com.things.link.task.application.TaskProjectCleanupContributor;
import com.things.link.task.infrastructure.persistence.JdbcTaskProjectCleanupRepository;
import com.things.link.rule.application.RuleProjectCleanupContributor;
import com.things.link.rule.infrastructure.persistence.JdbcRuleProjectCleanupRepository;
import com.things.link.alarm.application.AlarmProjectCleanupContributor;
import com.things.link.alarm.infrastructure.persistence.JdbcAlarmProjectCleanupRepository;
import com.things.link.enduser.application.AppProjectCleanupContributor;
import com.things.link.enduser.infrastructure.persistence.JdbcAppProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import com.things.link.device.application.DeviceProjectCleanupContributor;
import com.things.link.device.infrastructure.persistence.JdbcDeviceProjectCleanupRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.things.link.telemetry.application.TelemetryProjectCleanupContributor;
import com.things.link.telemetry.infrastructure.persistence.JdbcTelemetryProjectCleanupRepository;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0088：完整设备清理的实际删行上限、隐藏入边、事务围栏及旧库资格。 */
@Testcontainers
class ProjectCleanupDeviceTests {
    /** 独占真实Timescale版本，避免全局历史与邻居夹具相互影响。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_parent_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 本片新增清理表，固定表名仅用于独立真实快照，不来自请求输入。 */
    private static final List<String> TABLES = List.of("dev_group_member", "dev_tag", "dev_device",
            "dev_thing_model_version", "dev_event_parameter_definition", "dev_property_definition",
            "dev_event_definition", "dev_command_definition", "dev_data_stream", "dev_group", "dev_type");
    /** 迁移角色只用于建夹具和独立观察。 */
    private static JdbcTemplate owner;
    private static HikariDataSource ownerSource;
    private static HikariDataSource appSource;
    /** 真实APP角色执行被测清理。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原物理事务与五秒预算。 */
    private DataSourceTransactionManager transactions;
    /** project自身持久进度。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实准入及租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 六个历史前置贡献与当前看板空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测固定设备仓储。 */
    private JdbcDeviceProjectCleanupRepository devices;
    /** 包括真实DEVICE贡献的编排。 */
    private ProjectCleanupBatchService batches;

    /** 旧1110库完整图不被新索引/函数升级改写；1200仅迁移一次且不向PUBLIC授权。 */
    @BeforeAll
    static void migrate() {
        ownerSource = source("thingslink", "cleanup-device-owner");
        owner = new JdbcTemplate(ownerSource);
        flyway("20260905.1110").migrate();
        Fixture legacy = fixture(true, null);
        seed(legacy, 3);
        Map<String, String> before = snapshot(legacy);
        assertThat(flyway("20260905.1200").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1200").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        appSource = source("thingslink_app", "cleanup-device-app");
        assertThat(snapshot(legacy)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                + "WHERE p.oid='public.dev_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0", Long.class)).isZero();
    }

    @AfterAll
    static void closeSources() {
        if (appSource != null) appSource.close();
        if (ownerSource != null) ownerSource.close();
    }

    private static HikariDataSource source(String user, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(user);
        config.setPassword("thingslink");
        config.setPoolName(name);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    /** 原始点先清后复位隔离容器；生产项目仍走墓碑与受控贡献。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.ts_property_point_internal");
        owner.update("DELETE FROM public.sys_project");
        app = new JdbcTemplate(appSource);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(appSource);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        prerequisites = List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))),
                proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyContributor(),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))));
        devices = new JdbcDeviceProjectCleanupRepository(app);
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(proxy(new DeviceProjectCleanupContributor(devices)));
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** 十一表各1001行：每次提交真实总差恰等返回值，不隐藏CASCADE；软删、双邻居和审计都复核。 */
    @Test
    void drainsEveryParentTableInBoundedBatchesAndPreservesNeighbors() {
        Fixture first = fixture(false, null);
        Fixture neighbor = fixture(true, first);
        Fixture otherTenant = fixture(true, null);
        seed(first, 1001); seed(neighbor, 1); seed(otherTenant, 1);
        owner.update("UPDATE public.dev_device SET deleted_at=now() WHERE project_id=?", first.project());
        owner.update("UPDATE public.dev_property_definition SET deleted_at=now() WHERE project_id=?", first.project());
        owner.update("INSERT INTO public.dev_topo_repair_audit VALUES (gen_random_uuid(),?,?,'D-026',now(),'{}'::jsonb)", first.tenant(), first.project());
        Map<String, String> neighborBefore = snapshot(neighbor);
        Map<String, String> otherBefore = snapshot(otherTenant);
        String audit = auditSnapshot(first);
        ProjectCleanupClaim claim = deviceClaim();
        int deleted = 0;
        int nonEmpty = 0;
        boolean complete = false;
        for (int i = 0; i < 40; i++) {
            long before = total(first);
            ProjectCleanupBatchResult result = batches.execute(claim).orElseThrow();
            assertThat(result.blockedReason()).isNull();
            assertThat(before - total(first)).isEqualTo(result.deletedRows());
            assertThat(result.deletedRows()).isBetween(0, 500);
            assertThat(snapshot(neighbor)).isEqualTo(neighborBefore);
            assertThat(snapshot(otherTenant)).isEqualTo(otherBefore);
            assertThat(auditSnapshot(first)).isEqualTo(audit);
            deleted += result.deletedRows();
            if (result.deletedRows() > 0) nonEmpty++;
            if (result.complete()) { complete = true; break; }
            claim = admission.claimNext().orElseThrow();
        }
        assertThat(complete).isTrue();
        assertThat(deleted).isEqualTo(11011);
        assertThat(nonEmpty).isEqualTo(33);
        assertThat(total(first)).isZero();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("IAM");
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?", Long.class, first.project())).isEqualTo(11011);
    }

    /** 单列FK允许的跨项目历史不能被父删除级联，锁后检查必须看见APP自身看不见的入边。 */
    @ParameterizedTest
    @ValueSource(strings = {"device", "event", "type"})
    void blocksHiddenIncomingReferencesWithoutTouchingNeighbor(String parent) {
        Fixture first = fixture(false, null); seed(first, 1);
        Fixture neighbor = fixture(true, null);
        switch (parent) {
            case "device" -> owner.update("INSERT INTO public.dev_connection(id,tenant_id,project_id,device_id) VALUES (gen_random_uuid(),?,?,?)",
                    neighbor.tenant(), neighbor.project(), id("dev_device", first));
            case "event" -> owner.update("INSERT INTO public.dev_event_parameter_definition(id,tenant_id,project_id,event_id,parameter_key,name,data_type) VALUES (gen_random_uuid(),?,?,?,'hidden','隐藏参数','NUMBER')",
                    neighbor.tenant(), neighbor.project(), id("dev_event_definition", first));
            default -> owner.update("INSERT INTO public.dev_data_stream(id,tenant_id,project_id,device_type_id,stream_key,name,format) VALUES (gen_random_uuid(),?,?,?,'hidden','隐藏流','JSON')",
                    neighbor.tenant(), neighbor.project(), id("dev_type", first));
        }
        Map<String, String> before = snapshot(neighbor);
        String connection = tableSnapshot("dev_connection", neighbor);
        assertThat(drainToBlocked(deviceClaim(), first)).isEqualTo("DEVICE_PARENT_REFERENCE_REMAINS");
        assertThat(snapshot(neighbor)).isEqualTo(before);
        assertThat(tableSnapshot("dev_connection", neighbor)).isEqualTo(connection);
        assertThat(rows("dev_" + (parent.equals("device") ? "device" : parent.equals("event") ? "event_definition" : "type"), first)).isEqualTo(1);
    }

    /** 当前project错误tenant的标签不得被跳过或误报空域。 */
    @Test
    void rejectsScopeMismatchWithoutGuessingOwnership() {
        Fixture first = fixture(false, null); seed(first, 1);
        Fixture other = fixture(true, null);
        owner.update("UPDATE public.dev_tag SET tenant_id=? WHERE project_id=?", other.tenant(), first.project());
        assertThat(drainToBlocked(deviceClaim(), first)).isEqualTo("DEVICE_SCOPE_MISMATCH");
        assertThat(rows("dev_tag", first)).isEqualTo(1);
    }

    /** 旧raw在TELEMETRY后迟到的故障事实仍由0087拒绝，完整DEVICE不能借级联将它删除。 */
    @Test
    void blocksLateRawReferenceThroughExistingPrivateGuard() {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = deviceClaim();
        owner.update("""
                INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double,
                    data_type,thing_model_version_id,model_version)
                VALUES (?,?,'temperature',now()-interval '30 days',gen_random_uuid(),42,'NUMBER',?,'1.0.0')
                """, first.project(), id("dev_device", first), id("dev_thing_model_version", first));
        assertThat(drainToBlocked(claim, first)).isEqualTo("DEVICE_PARENT_REFERENCE_REMAINS");
        assertThat(rows("ts_property_point_internal", first)).isEqualTo(1);
        assertThat(rows("dev_thing_model_version", first)).isEqualTo(1);
    }

    /** 清理删除后发生异常或最终租约失效，全批数据与计数同时回滚，原领取可安全重入。 */
    @ParameterizedTest
    @ValueSource(strings = {"failure", "lease"})
    void rollsBackDeletionAndProgressAndAllowsRetry(String mode) {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = parentClaim();
        Map<String, String> before = snapshot(first);
        String progress = tableSnapshot("sys_project", first);
        assertThatThrownBy(() -> batch(c -> {
            for (String table : TABLES) app.execute("CREATE TEMP TABLE " + table + "(LIKE public." + table + ") ON COMMIT DROP");
            ProjectCleanupBatchResult result = devices.clean(c);
            assertThat(result.deletedRows()).isEqualTo(1);
            if (mode.equals("failure")) throw new IllegalStateException("受控删除后失败");
            app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
            return result;
        }).execute(claim)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(tableSnapshot("sys_project", first)).isEqualTo(progress);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 函数自身拒绝错误/过期能力，不能只依赖Java入口校验；原调用不建立RLS上下文亦不能越权。 */
    @ParameterizedTest
    @ValueSource(strings = {"tenant", "project", "generation", "token", "stage", "lease"})
    void rejectsForgedOrExpiredIdentity(String field) {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = deviceClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='IAM' WHERE id=?", first.project());
        if (field.equals("lease")) owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", first.project());
        ProjectCleanupClaim wrong = new ProjectCleanupClaim(field.equals("tenant") ? UUID.randomUUID() : claim.tenantId(),
                field.equals("project") ? UUID.randomUUID() : claim.projectId(), field.equals("generation") ? claim.generation()+1 : claim.generation(),
                claim.stage(), field.equals("token") ? UUID.randomUUID() : claim.leaseToken(), claim.leaseUntil(), false);
        Map<String, String> before = snapshot(first);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> devices.clean(wrong)), "42501");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 没有数据的已准入项目完成DEVICE；正常项目即使有伪造能力也不能物理清理。 */
    @Test
    void completesEmptyDomainAndRejectsActiveProject() {
        Fixture first = fixture(false, null);
        ProjectCleanupClaim claim = deviceClaim();
        assertThat(batches.execute(claim).orElseThrow().complete()).isTrue();
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?", String.class, first.project())).isEqualTo("IAM");
        Fixture active = fixture(true, null); seed(active, 1);
        ProjectCleanupClaim forged = new ProjectCleanupClaim(active.tenant(), active.project(), 0, "DEVICE", UUID.randomUUID(), java.time.Instant.now().plusSeconds(120), false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> devices.clean(forged)), "42501");
        assertThat(total(active)).isEqualTo(11);
    }

    /** 隐藏引用先持KEY SHARE时，父删除等待其提交后以新快照阻塞，不能使用旧的空引用结论。 */
    @Test
    void incomingReferenceWinsBeforeParentLock() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1);
        Fixture neighbor = fixture(true, null);
        ProjectCleanupClaim claim = parentClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            insertConnection(writer, neighbor, id("dev_device", first));
            var cleanup = workers.submit(() -> batches.execute(claim));
            try { awaitLock("SELECT * FROM public.dev_project_cleanup_batch%"); } finally { writer.commit(); }
            assertThat(cleanup.get(3, TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("DEVICE_PARENT_REFERENCE_REMAINS");
        }
        assertThat(rows("dev_device", first)).isEqualTo(1);
        assertThat(rows("dev_connection", neighbor)).isEqualTo(1);
    }

    /** 真实清理先锁删父时，后插入等到提交后由原FK拒绝；不关闭任何触发器。 */
    @Test
    void parentDeletionWinsBeforeIncomingReference() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1);
        Fixture neighbor = fixture(true, null);
        ProjectCleanupClaim claim = parentClaim();
        UUID device = id("dev_device", first);
        try (Connection cleanup = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
             var workers = Executors.newSingleThreadExecutor()) {
            cleanup.setAutoCommit(false);
            try (PreparedStatement query = cleanup.prepareStatement("SELECT * FROM public.dev_project_cleanup_batch(?,?,?,?)")) {
                query.setObject(1, claim.tenantId()); query.setObject(2, claim.projectId());
                query.setLong(3, claim.generation()); query.setObject(4, claim.leaseToken());
                try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt("deleted_rows")).isEqualTo(1); }
            }
            var insertion = workers.submit(() -> {
                try (Connection writer = connection()) { insertConnection(writer, neighbor, device); return (SQLException) null; }
                catch (SQLException failure) { return failure; }
            });
            try { awaitLock("INSERT INTO public.dev_connection%"); } finally { cleanup.commit(); }
            SQLException failure = insertion.get(3, TimeUnit.SECONDS);
            assertThat(failure != null).isTrue();
            assertThat(failure.getSQLState()).isEqualTo("23503");
        }
        assertThat(rows("dev_device", first)).isZero();
        assertThat(rows("dev_connection", neighbor)).isZero();
    }

    /** 直接SQL入口在父锁等待期间过期也必须回滚；不能仅依赖Java批次最后的进度CAS。 */
    @Test
    void rejectsLeaseConsumedWhileWaitingForParentEvenAtDatabaseEntry() throws Exception {
        Fixture first = fixture(false, null); seed(first, 1);
        ProjectCleanupClaim claim = parentClaim();
        try (Connection writer = connection(); var workers = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("SELECT id FROM public.dev_device WHERE project_id=? FOR UPDATE")) {
                lock.setObject(1, first.project()); lock.executeQuery().close();
            }
            owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()+interval '500 milliseconds' WHERE id=?", first.project());
            var cleanup = workers.submit(() -> {
                try { new TransactionTemplate(transactions).execute(status -> devices.clean(claim)); return (RuntimeException) null; }
                catch (RuntimeException failure) { return failure; }
            });
            try {
                awaitLock("SELECT * FROM public.dev_project_cleanup_batch%");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!Boolean.TRUE.equals(owner.queryForObject("SELECT cleanup_lease_until<=clock_timestamp() FROM public.sys_project WHERE id=?", Boolean.class, first.project()))) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("租约未按真实时钟到期");
                    Thread.sleep(10);
                }
            } finally { writer.commit(); }
            RuntimeException failure = cleanup.get(3, TimeUnit.SECONDS);
            assertThat(failure != null).as("直接数据库入口不能在父锁等待耗尽租约后提交删除").isTrue();
            assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
            assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("42501");
        }
        assertThat(rows("dev_device", first)).isEqualTo(1);
    }

    /** @return 六个历史真实前置和一个看板空域后的DEVICE领取 */
    private ProjectCleanupClaim deviceClaim() {
        for (int i = 0; i < ProjectCleanupStage.DEVICE.ordinal(); i++) assertThat(batches.execute(admission.claimNext().orElseThrow()).orElseThrow().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("DEVICE");
        return claim;
    }

    /** @return 先清成员和标签后，下一轮真实父设备候选 */
    private ProjectCleanupClaim parentClaim() {
        assertThat(batches.execute(deviceClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(batches.execute(admission.claimNext().orElseThrow()).orElseThrow().deletedRows()).isEqualTo(1);
        return admission.claimNext().orElseThrow();
    }

    /** @param claim 首轮身份 @param first 项目 @return 首个阻塞码，每个阻塞批次前后快照不变 */
    private String drainToBlocked(ProjectCleanupClaim claim, Fixture first) {
        for (int i = 0; i < 20; i++) {
            Map<String, String> before = snapshot(first);
            ProjectCleanupBatchResult result = batches.execute(claim).orElseThrow();
            if (result.blockedReason() != null) { assertThat(snapshot(first)).isEqualTo(before); return result.blockedReason(); }
            assertThat(result.complete()).isFalse();
            assertThat(result.deletedRows()).isBetween(1, 500);
            claim = admission.claimNext().orElseThrow();
        }
        throw new AssertionError("未到达应有的引用阻塞");
    }

    /** @param operation 故障包装 @return 保持原五秒事务的批次入口 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim, ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定设备领域阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.DEVICE; }
            /** 在原事务内调用实际清理或受控故障。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** @param active 是否正常项目 @param shared 同租户邻居 @return 可复现的完整项目身份 */
    private static Fixture fixture(boolean active, Fixture shared) {
        Fixture f = new Fixture(shared == null ? UUID.randomUUID() : shared.tenant(), UUID.randomUUID());
        if (shared == null) owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'设备清理租户')", f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'设备清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(), f.tenant(), "device_" + f.project().toString().replace("-", ""), active ? "ACTIVE" : "DELETING", active ? 0 : 1, active);
        return f;
    }

    /** @param f 项目 @param count 十一表同规模完整FK图；不禁用约束、不伪造级联计数 */
    private static void seed(Fixture f, int count) {
        owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol) SELECT gen_random_uuid(),?,?,'type-'||n,'测试类型','DIRECT','STANDARD' FROM generate_series(1,?) n", f.tenant(), f.project(), count);
        owner.update("""
                INSERT INTO public.dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,
                    version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                SELECT gen_random_uuid(),tenant_id,project_id,id,'1.0.0',1,0,0,'PATCH','TC_PROPERTY_COMPOSITE_V1',
                    '{}'::jsonb,encode(digest(convert_to('{}','UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256'
                  FROM public.dev_type WHERE project_id=?
                """, f.project());
        owner.update("""
                INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,thing_model_version_id,device_key,name)
                SELECT gen_random_uuid(),v.tenant_id,v.project_id,v.device_type_id,v.id,t.type_key,'测试设备'
                  FROM public.dev_thing_model_version v JOIN public.dev_type t ON t.id=v.device_type_id WHERE v.project_id=?
                """, f.project());
        owner.update("INSERT INTO public.dev_group(id,tenant_id,project_id,name,group_type) SELECT gen_random_uuid(),tenant_id,project_id,device_key,'STATIC' FROM public.dev_device WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_group_member(tenant_id,project_id,group_id,device_id) SELECT d.tenant_id,d.project_id,g.id,d.id FROM public.dev_device d JOIN public.dev_group g ON g.project_id=d.project_id AND g.name=d.device_key WHERE d.project_id=?", f.project());
        owner.update("INSERT INTO public.dev_tag(id,tenant_id,project_id,device_id,tag_key,tag_value) SELECT gen_random_uuid(),tenant_id,project_id,id,'location','测试' FROM public.dev_device WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_event_definition(id,tenant_id,project_id,device_type_id,event_key,name,level) SELECT gen_random_uuid(),tenant_id,project_id,id,'event','事件','INFO' FROM public.dev_type WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_event_parameter_definition(id,tenant_id,project_id,event_id,parameter_key,name,data_type) SELECT gen_random_uuid(),tenant_id,project_id,id,'parameter','参数','NUMBER' FROM public.dev_event_definition WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) SELECT gen_random_uuid(),tenant_id,project_id,id,'temperature','温度','REPORT','NUMBER' FROM public.dev_type WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name) SELECT gen_random_uuid(),tenant_id,project_id,id,'command','命令' FROM public.dev_type WHERE project_id=?", f.project());
        owner.update("INSERT INTO public.dev_data_stream(id,tenant_id,project_id,device_type_id,stream_key,name,format) SELECT gen_random_uuid(),tenant_id,project_id,id,'stream','数据流','JSON' FROM public.dev_type WHERE project_id=?", f.project());
    }

    /** @param table 固定夹具表 @param f 项目 @return 单行夹具的真实主键 */
    private static UUID id(String table, Fixture f) { return owner.queryForObject("SELECT id FROM public." + table + " WHERE project_id=?", UUID.class, f.project()); }
    /** @param table 固定表 @param f 项目 @return 独立真实行数 */
    private static long rows(String table, Fixture f) { return owner.queryForObject("SELECT count(*) FROM public." + table + " WHERE project_id=?", Long.class, f.project()); }
    /** @param f 项目 @return 十一表真实总量，用于捕获任何隐式级联 */
    private static long total(Fixture f) { return TABLES.stream().mapToLong(t -> rows(t, f)).sum(); }
    /** @param table 固定表 @param f 项目 @return 全字段快照；项目本身使用id */
    private static String tableSnapshot(String table, Fixture f) {
        return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY to_jsonb(d)::text),'[]'::jsonb)::text FROM public." + table + " d WHERE " + (table.equals("sys_project") ? "id" : "project_id") + "=?", String.class, f.project());
    }
    /** @param f 项目 @return 所有新父实体/子行的完整快照 */
    private static Map<String, String> snapshot(Fixture f) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : TABLES) result.put(table, tableSnapshot(table, f));
        return result;
    }
    /** @param f 项目 @return 不可变拓扑修复证据 */
    private static String auditSnapshot(Fixture f) { return tableSnapshot("dev_topo_repair_audit", f); }
    /** @return 真实owner物理连接，仅用于有界并发夹具 */
    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"); }
    /** @param c 独立事务 @param f 隐藏邻居 @param device 真实父设备 */
    private static void insertConnection(Connection c, Fixture f, UUID device) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO public.dev_connection(id,tenant_id,project_id,device_id) VALUES (gen_random_uuid(),?,?,?)")) {
            insert.setObject(1, f.tenant()); insert.setObject(2, f.project()); insert.setObject(3, device); insert.executeUpdate();
        }
    }
    /** @param query 实际被测语句前缀；观察服务端锁等待才释放另一事务 */
    private static void awaitLock(String query) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, query))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        throw new AssertionError("未观察到预期FK锁等待");
    }
    /** @param action 真实SQL @param state 核对确切失败来源 */
    private static void assertSqlState(Runnable action, String state) {
        assertThatThrownBy(action::run).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo(state));
    }
    /** @param target 被代理对象 @return 使用真实事务的注解代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    /** @param target 固定升级边界 @return 工作区全部模块的真实Flyway迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    /** @param tenant 实际租户 @param project 实际项目 */
    private record Fixture(UUID tenant, UUID project) { }
}
