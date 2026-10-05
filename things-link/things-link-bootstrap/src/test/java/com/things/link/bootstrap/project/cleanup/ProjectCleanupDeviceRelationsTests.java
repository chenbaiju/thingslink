package com.things.link.bootstrap.project.cleanup;

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
import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.things.link.telemetry.application.TelemetryProjectCleanupContributor;
import com.things.link.telemetry.infrastructure.persistence.JdbcTelemetryProjectCleanupRepository;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

/** ADR0086：真实拓扑双写、有界附属关系与实际租约等待的独立资格。 */
@Testcontainers
class ProjectCleanupDeviceRelationsTests {
    /** 独占PG保留真实延期触发器及旧库升级。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_relations_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 六表固定快照源，测试不读取其他领域业务表。 */
    private static final List<String> TABLES=List.of("dev_modbus_poll","dev_modbus_point_mapping","dev_topo",
            "dev_connection","dev_credential","dev_shadow");
    /** 迁移及全局真实观察。 */
    private static JdbcTemplate owner;
    /** 实际应用权限。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原物理事务。 */
    private DataSourceTransactionManager transactions;
    /** 项目持久进度。 */
    private JdbcProjectCleanupRepository projects;
    /** 实际准入和租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 六个历史前置领域真实执行，看板以当前顺序完成空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 附属关系子域不能提前推进IAM。 */
    private ProjectCleanupBatchService batches;

    /** 0900旧库保持存量设备，再仅升级1000且重复零迁移。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.0900").migrate();
        Fixture legacy=fixture(true,null);
        seed(legacy,1);
        Map<String,String> before=snapshot(legacy);
        assertThat(flyway("20260905.1000").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1000").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        assertThat(snapshot(legacy)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                +"WHERE p.oid='public.dev_device_relations_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Long.class)).isZero();
    }

    /** 原owner父级联复位本类夹具，生产项目墓碑路径必须使用有界端口。 */
    @BeforeEach
    void prepare() {
        owner.update("DELETE FROM public.sys_project");
        DriverManagerDataSource source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink");
        app=new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions=new DataSourceTransactionManager(source);
        projects=new JdbcProjectCleanupRepository(app);
        admission=proxy(new ProjectCleanupAdmissionService(projects,new AuditLogService(app,new ObjectMapper()),app));
        prerequisites=List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))),
                proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyContributor(),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))));
        batches=batch(this::relations);
    }

    /** 六表及历史/有效边各1001，23个删除批次7007行；每次提交都维持投影，邻居及修复审计不变。 */
    @Test
    void drainsBoundedRelationsWithAtomicTopologyAndPreservedNeighbor() {
        Fixture first=fixture(false,null);
        Fixture neighbor=fixture(true,first);
        seed(first,1001); seed(neighbor,1);
        Map<String,String> before=snapshot(neighbor);
        String audit=auditSnapshot(first);
        ProjectCleanupClaim claim=deviceClaim();
        int deleted=0;
        int count=0;
        for (int i=0;i<30;i++) {
            ProjectCleanupBatchResult result=batches.execute(claim).orElseThrow();
            assertThat(snapshot(neighbor)).isEqualTo(before);
            assertProjection(first);
            if (result.blockedReason()!=null) {
                assertThat(result.blockedReason()).isEqualTo("DEVICE_REMAINING");
                break;
            }
            assertThat(result.deletedRows()).isBetween(1,500);
            deleted+=result.deletedRows(); count++;
            claim=admission.claimNext().orElseThrow();
        }
        assertThat(deleted).isEqualTo(7007);
        assertThat(count).isEqualTo(23);
        assertThat(auditSnapshot(first)).isEqualTo(audit);
        for (String table:TABLES) assertThat(rows(table,first)).isZero();
        assertThat(rows("dev_device",first)).isEqualTo(1003);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(7007);
    }

    /** 只删有效边的普通SQL仍23514；有界端口同时清投影后提交成功。 */
    @Test
    void preservesDeferredProjectionGuardAndLimitsActiveEdgesToTwoHundredAndFifty() {
        Fixture first=fixture(false,null);
        seedTopology(first,1001,false);
        assertSqlState(() -> owner.update("DELETE FROM public.dev_topo WHERE project_id=?",first.project()),"23514");
        assertThat(batches.execute(deviceClaim()).orElseThrow().deletedRows()).isEqualTo(250);
        assertThat(rows("dev_topo",first)).isEqualTo(751);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.dev_device WHERE project_id=? AND gateway_id IS NOT NULL",Long.class,first.project())).isEqualTo(751);
        assertProjection(first);
    }

    /** 真租约尚未到期等待；过期IDLE或IN_FLIGHT可清，最新维护时间和未来下一周期不延长保留。 */
    @ParameterizedTest
    @ValueSource(strings={"IDLE","IN_FLIGHT"})
    void waitsOnlyForActualPollLease(String state) {
        Fixture first=fixture(false,null); seedPoll(first,1);
        UUID pollId=owner.queryForObject("SELECT id FROM public.dev_modbus_poll WHERE project_id=?",UUID.class,first.project());
        UUID requestId=UUID.randomUUID();
        owner.update("UPDATE public.dev_modbus_poll SET request_id=?,status=?,lease_until=clock_timestamp()+interval '1 minute',updated_at=now(),next_poll_at=now()+interval '1 day' WHERE project_id=?",requestId,state,first.project());
        assertThat(batches.execute(deviceClaim()).orElseThrow().blockedReason()).isEqualTo("DEVICE_POLL_IN_FLIGHT");
        assertThat(rows("dev_modbus_poll",first)).isEqualTo(1);
        owner.update("UPDATE public.dev_modbus_poll SET lease_until=clock_timestamp()-interval '1 second' WHERE project_id=?",first.project());
        allowRetry(first);
        assertThat(next().deletedRows()).isEqualTo(1);
        JdbcModbusPollRepository runtime=new JdbcModbusPollRepository(app);
        var claimed=new TransactionTemplate(transactions).execute(status -> runtime.claimDue(10));
        assertThat(claimed).isEmpty();
        assertThat(runtime.findByRequestId(requestId)).isEmpty();
        assertThat(runtime.completeRequest(pollId,requestId,java.time.Instant.now())).isFalse();
    }

    /** DELETE实际等到行锁后续租，必须留下原行而非使用候选旧租约。 */
    @Test
    void rechecksPollLeaseAfterWaitingForWriter() throws Exception {
        Fixture first=fixture(false,null); seedPoll(first,1);
        ProjectCleanupClaim claim=deviceClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement update=writer.prepareStatement("UPDATE public.dev_modbus_poll SET lease_until=clock_timestamp()+interval '1 minute' WHERE project_id=?")) {
                update.setObject(1,first.project()); update.executeUpdate();
            }
            var cleanup=workers.submit(() -> batches.execute(claim));
            try { awaitCleanupLock(); } finally { writer.commit(); }
            assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("DEVICE_RELATIONS_FACT_CHANGED");
        }
        assertThat(rows("dev_modbus_poll",first)).isEqualTo(1);
    }

    /** 历史边在DELETE等待期间复活，必须重新按有效边双写，不可沿旧历史候选只删边。 */
    @Test
    void handlesClosedEdgeReactivatedWhileDeletionWaits() throws Exception {
        Fixture first=fixture(false,null);
        owner.update("INSERT INTO public.dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source,unbound_at) VALUES (gen_random_uuid(),?,?,?,?,'CONTROL_PLANE',now())",first.tenant(),first.project(),first.gateway(),first.sub());
        ProjectCleanupClaim claim=deviceClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement revive=writer.prepareStatement("UPDATE public.dev_topo SET unbound_at=NULL WHERE project_id=?")) {
                revive.setObject(1,first.project()); revive.executeUpdate();
            }
            try (PreparedStatement project=writer.prepareStatement("UPDATE public.dev_device SET gateway_id=? WHERE id=?")) {
                project.setObject(1,first.gateway()); project.setObject(2,first.sub()); project.executeUpdate();
            }
            var cleanup=workers.submit(() -> batches.execute(claim));
            try { awaitCleanupLock(); } finally { writer.commit(); }
            assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().deletedRows()).isEqualTo(1);
        }
        assertThat(rows("dev_topo",first)).isZero();
        assertProjection(first);
    }

    /** 子设备被其他事务锁住时NOWAIT回滚，不留下已删除边或半批投影。 */
    @Test
    void rejectsBusyProjectionWithoutPartialTopologyDeletion() throws Exception {
        Fixture first=fixture(false,null); seedTopology(first,1,false);
        Map<String,String> before=snapshot(first);
        ProjectCleanupClaim claim=deviceClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock=writer.prepareStatement("SELECT id FROM public.dev_device WHERE id=? FOR UPDATE")) {
                lock.setObject(1,first.sub()); lock.executeQuery().close();
            }
            assertSqlState(() -> batches.execute(claim),"55P03");
            writer.rollback();
        }
        assertThat(snapshot(first)).isEqualTo(before);
        assertProjection(first);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** TEMP表不改变来源，投影双写后异常或最终失权会一同回滚，原token可重试。 */
    @ParameterizedTest
    @ValueSource(strings={"failure","lease"})
    void rollsBackTopologyAndProgressAfterDeletion(String mode) {
        Fixture first=fixture(false,null); seedTopology(first,1,false);
        Map<String,String> before=snapshot(first);
        ProjectCleanupClaim claim=deviceClaim();
        assertThatThrownBy(() -> batch(c -> {
            for (String table:TABLES) app.execute("CREATE TEMP TABLE "+table+"(LIKE public."+table+") ON COMMIT DROP");
            assertThat(relations(c).deletedRows()).isEqualTo(1);
            if (mode.equals("failure")) throw new IllegalStateException("拓扑双写后受控失败");
            app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
            return ProjectCleanupBatchResult.deleted(1);
        }).execute(claim)).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(first)).isEqualTo(before);
        assertProjection(first);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 完整身份错误在任何附属表删除之前42501，不泄漏或改邻居事实。 */
    @ParameterizedTest
    @ValueSource(strings={"tenant","project","generation","token","stage"})
    void rejectsWrongCleanupIdentity(String field) {
        Fixture first=fixture(false,null); seedPoll(first,1);
        ProjectCleanupClaim claim=deviceClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='IAM' WHERE id=?",first.project());
        ProjectCleanupClaim wrong=new ProjectCleanupClaim(field.equals("tenant")?UUID.randomUUID():claim.tenantId(),
                field.equals("project")?UUID.randomUUID():claim.projectId(),field.equals("generation")?claim.generation()+1:claim.generation(),
                claim.stage(),field.equals("token")?UUID.randomUUID():claim.leaseToken(),claim.leaseUntil(),false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> relations(wrong)),"42501");
        assertThat(rows("dev_modbus_poll",first)).isEqualTo(1);
    }

    /** tenant不一致的存量连接不猜测修复，也不把其他表空当完整子域完成。 */
    @Test
    void blocksMismatchedTenantResidue() {
        Fixture first=fixture(false,null); Fixture neighbor=fixture(true,null);
        owner.update("INSERT INTO public.dev_connection(id,tenant_id,project_id,device_id) VALUES (gen_random_uuid(),?,?,?)",neighbor.tenant(),first.project(),first.direct());
        Map<String,String> before=snapshot(first);
        assertThat(batches.execute(deviceClaim()).orElseThrow().blockedReason()).isEqualTo("DEVICE_RELATIONS_FACT_CHANGED");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 无附属数据只完成子域，设备父行及DEVICE阶段继续保留。 */
    @Test
    void emptyRelationsDoNotAdvanceWholeDeviceStage() {
        Fixture first=fixture(false,null);
        assertThat(batches.execute(deviceClaim()).orElseThrow().blockedReason()).isEqualTo("DEVICE_REMAINING");
        assertThat(rows("dev_device",first)).isEqualTo(3);
    }

    /** @param claim 实际领取 @return 附属关系空只映射为待完成DEVICE */
    private ProjectCleanupBatchResult relations(ProjectCleanupClaim claim) {
        ProjectCleanupBatchResult result=app.queryForObject("SELECT * FROM public.dev_device_relations_cleanup_batch(?,?,?,?)",
                (row,number) -> new ProjectCleanupBatchResult(row.getInt("deleted_rows"),row.getBoolean("complete"),row.getString("blocked_reason")),
                claim.tenantId(),claim.projectId(),claim.generation(),claim.leaseToken());
        return result.complete()?ProjectCleanupBatchResult.blocked("DEVICE_REMAINING"):result;
    }

    /** @return 六个历史真实前置和一个看板空域后的DEVICE领取 */
    private ProjectCleanupClaim deviceClaim() {
        for (int i = 0; i < ProjectCleanupStage.DEVICE.ordinal(); i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("DEVICE");
        return claim;
    }

    /** @return 数据库下一批 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }

    /** @param operation 真实附属端口或故障包装 @return 原五秒事务测试适配器 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors=new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定附属子域所属阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.DEVICE; }
            /** 在原事务内调用真实SQL。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** @param active 项目是否正常 @param shared 共用tenant邻居 @return 三种真实设备类型及其设备 */
    private static Fixture fixture(boolean active,Fixture shared) {
        Fixture f=new Fixture(shared==null?UUID.randomUUID():shared.tenant(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        if (shared==null) owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'附属清理租户')",f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'附属清理项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(),f.tenant(),"relation_"+f.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        for (String kind:List.of("DIRECT","GATEWAY","SUB_DEVICE")) {
            UUID type=kind.equals("SUB_DEVICE")?f.subType():UUID.randomUUID();
            owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,status) VALUES (?,?,?,?,?,?,?,'DRAFT')",
                    type,f.tenant(),f.project(),kind,kind,kind,kind.equals("GATEWAY")?"STANDARD_GATEWAY":"STANDARD");
            UUID device=switch(kind) { case "DIRECT" -> f.direct(); case "GATEWAY" -> f.gateway(); default -> f.sub(); };
            owner.update("INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,?,?)",device,f.tenant(),f.project(),type,kind,kind);
        }
        return f;
    }

    /** @param f 完整项目 @param count 六表每组数量，历史边及有效边各一组 */
    private static void seed(Fixture f,int count) {
        seedTopology(f,count,true); seedPoll(f,count);
        owner.update("""
                INSERT INTO public.dev_modbus_point_mapping(id,tenant_id,project_id,device_id,sub_device_id,property_key,slave_address,
                    function_code,register_address,data_type,byte_order,polling_interval_ms,status)
                SELECT gen_random_uuid(),?,?,?,?,'temperature',1,'READ_HOLDING_REGISTERS',n,'INT16','BIG_ENDIAN',1000,'PUBLISHED'
                  FROM generate_series(1,?) n
                """,f.tenant(),f.project(),f.gateway(),f.sub(),count);
        owner.update("INSERT INTO public.dev_connection(id,tenant_id,project_id,device_id) SELECT gen_random_uuid(),?,?,? FROM generate_series(1,?)",f.tenant(),f.project(),f.direct(),count);
        owner.update("""
                INSERT INTO public.dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name,deleted_at)
                SELECT gen_random_uuid(),?,?,?,'ACCESS_TOKEN',repeat('a',64),'历史凭据',now()-interval '31 days' FROM generate_series(1,?)
                """,f.tenant(),f.project(),f.direct(),count);
        owner.update("INSERT INTO public.dev_shadow(device_id,tenant_id,project_id,reported) SELECT id,tenant_id,project_id,jsonb_build_object('temperature',42) FROM public.dev_device WHERE project_id=? AND device_type_id=?",f.project(),f.subType());
    }

    /** @param f 项目 @param count 真实轮询点数 */
    private static void seedPoll(Fixture f,int count) {
        owner.update("""
                INSERT INTO public.dev_modbus_poll(id,tenant_id,project_id,device_id,sub_device_id,project_key,gateway_key,property_key,
                    slave_address,function_code,register_address,data_type,byte_order,polling_interval_ms)
                SELECT gen_random_uuid(),?,?,?,?,'project','gateway','temperature',1,'READ_HOLDING_REGISTERS',n,'INT16','BIG_ENDIAN',1000
                  FROM generate_series(1,?) n
                """,f.tenant(),f.project(),f.gateway(),f.sub(),count);
    }

    /** @param f 项目 @param count 真实子设备数 @param historical 是否另建同量已关闭边及保留修复证据 */
    private static void seedTopology(Fixture f,int count,boolean historical) {
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        transaction.executeWithoutResult(status -> {
            owner.update("INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,device_key,name) SELECT gen_random_uuid(),?,?,?,'sub-'||n,'子设备' FROM generate_series(1,?) n",f.tenant(),f.project(),f.subType(),count-1);
            owner.update("""
                    INSERT INTO public.dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source)
                    SELECT gen_random_uuid(),tenant_id,project_id,?,id,'CONTROL_PLANE' FROM public.dev_device WHERE project_id=? AND device_type_id=?
                    """,f.gateway(),f.project(),f.subType());
            owner.update("UPDATE public.dev_device SET gateway_id=? WHERE project_id=? AND device_type_id=?",f.gateway(),f.project(),f.subType());
            if (historical) {
                owner.update("""
                        INSERT INTO public.dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source,bound_at,unbound_at)
                        SELECT gen_random_uuid(),tenant_id,project_id,?,id,'CONTROL_PLANE',now()-interval '40 days',now()-interval '31 days'
                          FROM public.dev_device WHERE project_id=? AND device_type_id=?
                        """,f.gateway(),f.project(),f.subType());
                owner.update("""
                        INSERT INTO public.dev_topo_repair_audit(topology_id,tenant_id,project_id,repair_source,repaired_at,before_state)
                        SELECT id,tenant_id,project_id,'D-026',now(),to_jsonb(t) FROM public.dev_topo t WHERE project_id=? AND unbound_at IS NOT NULL
                        """,f.project());
            }
        });
    }

    /** @param f 项目 @return 六表和设备当前投影完整快照 */
    private static Map<String,String> snapshot(Fixture f) {
        Map<String,String> result=new LinkedHashMap<>();
        List<String> tables=new ArrayList<>(TABLES); tables.add("dev_device");
        for (String table:tables) result.put(table,owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY to_jsonb(d)::text),'[]'::jsonb)::text FROM public."+table+" d WHERE project_id=?",String.class,f.project()));
        return result;
    }

    /** @param f 项目 @return 不可变修复证据快照 */
    private static String auditSnapshot(Fixture f) {
        return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY topology_id),'[]'::jsonb)::text FROM public.dev_topo_repair_audit a WHERE project_id=?",String.class,f.project());
    }

    /** @param table 固定本域表 @param f 项目 @return 真实全局行数 */
    private long rows(String table,Fixture f) { return owner.queryForObject("SELECT count(*) FROM public."+table+" WHERE project_id=?",Long.class,f.project()); }

    /** @param f 每批提交后再次观察真实投影，不仅依赖SQL返回值 */
    private void assertProjection(Fixture f) {
        assertThat(owner.queryForObject("SELECT count(*) FROM public.dev_device d WHERE d.project_id=? AND d.gateway_id IS DISTINCT FROM (SELECT gateway_device_id FROM public.dev_topo WHERE sub_device_id=d.id AND unbound_at IS NULL)",Long.class,f.project())).isZero();
    }

    /** @param f 仅缩短测试的三十秒失败退避，不伪造业务租约 */
    private void allowRetry(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",f.project()); }

    /** 观察真实SQL行锁等待后才允许续租提交。 */
    private void awaitCleanupLock() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM public.dev_device_relations_cleanup_batch%')",Boolean.class))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        throw new AssertionError("未观察到附属关系清理锁等待");
    }

    /** @param action 真实SQL写入 @param state 不把其他前置错误冒充不可变保护 */
    private static void assertSqlState(Runnable action,String state) {
        assertThatThrownBy(action::run).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException)failure.getCause()).getSQLState()).isEqualTo(state));
    }

    /** @param target 原事务对象 @return 实际注解代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    /** @param target 固定升级截止 @return 完整工作区迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 三种设备角色明确，历史边与有效投影在同一真实事务建立。 */
    private record Fixture(UUID tenant,UUID project,UUID direct,UUID gateway,UUID sub,UUID subType) { }
}
