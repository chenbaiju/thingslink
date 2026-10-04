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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0085：真实历史清理能力、不可变普通写边界及原事务500行上限。 */
@Testcontainers
class ProjectCleanupDeviceHistoryTests {

    /** 独占PG验证真实权限、触发器、父级联和旧库升级。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_history_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 版本快照由数据库规范JSONB计算摘要，不伪造哈希或关闭不可变触发器。 */
    private static final String MODEL="{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"READ_ONLY\"}}}";
    /** owner初始化与观察，普通写同样受到历史守卫保护。 */
    private static JdbcTemplate owner;
    /** 实际APP连接。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 真实原事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 项目权威清理仓储。 */
    private JdbcProjectCleanupRepository projects;
    /** 实际准入、审计与租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 六个历史先行领域真实证明为空，看板以当前顺序完成空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 历史子域完成保持DEVICE，不能误进IAM。 */
    private ProjectCleanupBatchService batches;

    /** 0800旧库先证明普通删除23514，0900只增限定能力，不改写存量历史。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.0800").migrate();
        Fixture legacy=fixture(false,null,3);
        String before=historySnapshot(legacy);
        assertSqlState(() -> owner.update("DELETE FROM public.dev_device_model_binding_history WHERE project_id=?",legacy.project()),"23514");
        assertThat(flyway("20260905.0900").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0900").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        assertThat(historySnapshot(legacy)).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                +"WHERE p.oid='public.dev_device_binding_history_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Long.class)).isZero();
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
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))));
        batches=batch(this::history);
    }

    /** 同tenant两个项目，1001条历史恰好三批，当前设备/两个版本/邻居历史均保持原值。 */
    @Test
    void deletesOneThousandAndOneHistoryRowsWithoutChangingSharedTenantNeighborOrParents() {
        Fixture first=fixture(false,null,1001);
        Fixture neighbor=fixture(true,first,3);
        String device=entity("dev_device",first.device());
        String versionA=entity("dev_thing_model_version",first.versionA());
        String versionB=entity("dev_thing_model_version",first.versionB());
        String before=historySnapshot(neighbor);
        ProjectCleanupClaim claim=deviceClaim();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
        assertThat(count(first)).isEqualTo(501);
        batches=batch(this::history);
        assertThat(next().deletedRows()).isEqualTo(500);
        assertThat(next().deletedRows()).isEqualTo(1);
        assertThat(next().blockedReason()).isEqualTo("DEVICE_REMAINING");
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(1001);
        assertThat(owner.queryForObject("SELECT cleanup_batches FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(3);
        assertThat(historySnapshot(neighbor)).isEqualTo(before);
        assertThat(entity("dev_device",first.device())).isEqualTo(device);
        assertThat(entity("dev_thing_model_version",first.versionA())).isEqualTo(versionA);
        assertThat(entity("dev_thing_model_version",first.versionB())).isEqualTo(versionB);
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?",String.class,first.project())).isEqualTo("DEVICE");
    }

    /** APP仍无直接三写权限；owner普通三写仍23514，不因已经PURGING而自动放行。 */
    @ParameterizedTest
    @ValueSource(strings={"UPDATE","DELETE","TRUNCATE"})
    void preservesOrdinaryImmutabilityForAppAndOwner(String operation) {
        Fixture first=fixture(false,null,3);
        deviceClaim();
        String before=historySnapshot(first);
        String sql=switch(operation) {
            case "UPDATE" -> "UPDATE public.dev_device_model_binding_history SET effective_at=effective_at";
            case "DELETE" -> "DELETE FROM public.dev_device_model_binding_history";
            default -> "TRUNCATE public.dev_device_model_binding_history";
        };
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> {
            scope(first); return app.update(sql);
        }),"42501");
        assertSqlState(() -> owner.update(sql),"23514");
        assertThat(historySnapshot(first)).isEqualTo(before);
    }

    /** 清理能力仅准许DELETE；持真实能力的owner仍不可UPDATE或TRUNCATE，能力错误字符串也不能放行DELETE。 */
    @Test
    void capabilityDoesNotAuthorizeRewritingOrTruncatingHistory() {
        Fixture first=fixture(false,null,3);
        ProjectCleanupClaim claim=deviceClaim();
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        for (String sql:List.of("UPDATE public.dev_device_model_binding_history SET effective_at=now()",
                "TRUNCATE public.dev_device_model_binding_history")) {
            assertSqlState(() -> transaction.execute(status -> {
                owner.queryForObject("SELECT set_config('app.cleanup_history_generation',?,true)",String.class,Long.toString(claim.generation()));
                owner.queryForObject("SELECT set_config('app.cleanup_history_token',?,true)",String.class,claim.leaseToken().toString());
                return owner.update(sql);
            }),"23514");
        }
        assertSqlState(() -> transaction.execute(status -> {
            owner.queryForObject("SELECT set_config('app.cleanup_history_generation','bad-generation',true)",String.class);
            owner.queryForObject("SELECT set_config('app.cleanup_history_token','bad-token',true)",String.class);
            return owner.update("DELETE FROM public.dev_device_model_binding_history WHERE project_id=?",first.project());
        }),"23514");
        assertThat(count(first)).isEqualTo(3);
    }

    /** 完整身份中的tenant、project、代次、token或持久阶段不符都42501且保持历史。 */
    @ParameterizedTest
    @ValueSource(strings={"tenant","project","generation","token","stage"})
    void rejectsWrongCleanupIdentity(String field) {
        Fixture first=fixture(false,null,3);
        ProjectCleanupClaim claim=deviceClaim();
        if (field.equals("stage")) owner.update("UPDATE public.sys_project SET cleanup_stage='IAM' WHERE id=?",first.project());
        ProjectCleanupClaim wrong=new ProjectCleanupClaim(field.equals("tenant")?UUID.randomUUID():claim.tenantId(),
                field.equals("project")?UUID.randomUUID():claim.projectId(),field.equals("generation")?claim.generation()+1:claim.generation(),
                claim.stage(),field.equals("token")?UUID.randomUUID():claim.leaseToken(),claim.leaseUntil(),false);
        assertSqlState(() -> new TransactionTemplate(transactions).execute(status -> history(wrong)),"42501");
        assertThat(count(first)).isEqualTo(3);
    }

    /** TEMP不改真实来源，原事务设置逐字还原；函数返回不残留新清理能力。 */
    @Test
    void restoresOriginalContextAndIgnoresTemporaryHistoryTable() {
        Fixture first=fixture(false,null,3);
        ProjectCleanupClaim claim=deviceClaim();
        assertThat(batch(c -> {
            app.queryForObject("SELECT set_config('app.cleanup_history_generation','previous-generation',true)",String.class);
            app.queryForObject("SELECT set_config('app.cleanup_history_token','previous-token',true)",String.class);
            app.execute("CREATE TEMP TABLE dev_device_model_binding_history(LIKE public.dev_device_model_binding_history) ON COMMIT DROP");
            ProjectCleanupBatchResult result=history(c);
            assertThat(app.queryForObject("SELECT current_setting('app.cleanup_history_generation')",String.class)).isEqualTo("previous-generation");
            assertThat(app.queryForObject("SELECT current_setting('app.cleanup_history_token')",String.class)).isEqualTo("previous-token");
            return result;
        }).execute(claim).orElseThrow().deletedRows()).isEqualTo(3);
        assertThat(count(first)).isZero();
    }

    /** 删除后异常或最终租约失效均回滚历史，原claim可重试，不留下半批设备转换记录。 */
    @ParameterizedTest
    @ValueSource(strings={"failure","lease"})
    void rollsBackDeletionAndCapabilityOnFailure(String mode) {
        Fixture first=fixture(false,null,1001);
        ProjectCleanupClaim claim=deviceClaim();
        String before=historySnapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            ProjectCleanupBatchResult result=history(c);
            assertThat(result.deletedRows()).isEqualTo(500);
            if (mode.equals("failure")) throw new IllegalStateException("历史能力删除后故障");
            app.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
            return result;
        }).execute(claim)).isInstanceOf(IllegalStateException.class);
        assertThat(historySnapshot(first)).isEqualTo(before);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(500);
    }

    /** 原真实设备父级联仍可用；版本和项目保持，不能误把普通历史UPDATE保护扩为设备不可物理删除。 */
    @Test
    void preservesExistingPhysicalParentCascadeWithoutPurgeContext() {
        Fixture first=fixture(true,null,3);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            scope(first);
            assertThat(app.update("DELETE FROM public.dev_device WHERE id=?",first.device())).isEqualTo(1);
        });
        assertThat(count(first)).isZero();
        assertThat(entity("dev_thing_model_version",first.versionA())).isNotBlank();
    }

    /** 空历史只完成子域，设备和模型留给完整DEVICE后续切片。 */
    @Test
    void completesEmptyHistorySubdomain() {
        Fixture first=fixture(false,null,0);
        assertThat(batches.execute(deviceClaim()).orElseThrow().blockedReason()).isEqualTo("DEVICE_REMAINING");
        assertThat(entity("dev_device",first.device())).isNotBlank();
    }

    /** 存量tenant不一致的历史不猜测修复；合法行清完后明确阻塞，异常行逐字段保留。 */
    @Test
    void blocksMismatchedTenantHistoryInsteadOfReportingDomainComplete() {
        Fixture first=fixture(false,null,0);
        Fixture neighbor=fixture(true,null,0);
        owner.update("""
                INSERT INTO public.dev_device_model_binding_history(id,tenant_id,project_id,device_id,to_model_version_id,
                    transition_key,transition_type,effective_at)
                VALUES (gen_random_uuid(),?,?,?, ?,gen_random_uuid(),'INITIAL',now()-interval '40 days')
                """,neighbor.tenant(),first.project(),first.device(),first.versionA());
        String before=historySnapshot(first);
        assertThat(batches.execute(deviceClaim()).orElseThrow().blockedReason()).isEqualTo("DEVICE_HISTORY_SCOPE_MISMATCH");
        assertThat(historySnapshot(first)).isEqualTo(before);
    }

    /** @param claim 真实原事务领取 @return 只映射历史子域结果，不提前推进整体阶段 */
    private ProjectCleanupBatchResult history(ProjectCleanupClaim claim) {
        ProjectCleanupBatchResult result=app.queryForObject("SELECT * FROM public.dev_device_binding_history_cleanup_batch(?,?,?,?)",
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

    /** @param operation 真实历史端口或故障包装 @return 原五秒事务测试适配器 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors=new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定历史子域所属阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.DEVICE; }
            /** 在原事务内调用真实SQL。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** @param active 邻居或普通路径是否可用 @param shared 可共用tenant @param histories 连贯INITIAL/UPGRADE/ROLLBACK数 */
    private static Fixture fixture(boolean active,Fixture shared,int histories) {
        Fixture f=new Fixture(shared==null?UUID.randomUUID():shared.tenant(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        if (shared==null) owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'绑定历史清理租户')",f.tenant());
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'历史能力项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(),f.tenant(),"histcap_"+f.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,status) VALUES (?,?,?,'type','历史类型','DIRECT','STANDARD','PUBLISHED')",f.type(),f.tenant(),f.project());
        for (int patch=0;patch<2;patch++) owner.update("""
                INSERT INTO public.dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,version_major,version_minor,
                    version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,?,1,0,?,'PATCH','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                """,patch==0?f.versionA():f.versionB(),f.tenant(),f.project(),f.type(),"1.0."+patch,patch,MODEL,MODEL);
        owner.update("INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,thing_model_version_id,device_key,name) VALUES (?,?,?,?,?,'device','历史设备')",
                f.device(),f.tenant(),f.project(),f.type(),histories%2==0&&histories>0?f.versionB():f.versionA());
        owner.update("""
                INSERT INTO public.dev_device_model_binding_history(id,tenant_id,project_id,device_id,from_model_version_id,to_model_version_id,
                    transition_key,transition_type,effective_at,created_at)
                SELECT gen_random_uuid(),?,?,?,CASE WHEN n=1 THEN NULL WHEN n%2=0 THEN ?::uuid ELSE ?::uuid END,
                    CASE WHEN n%2=0 THEN ?::uuid ELSE ?::uuid END,gen_random_uuid(),
                    CASE WHEN n=1 THEN 'INITIAL' WHEN n%2=0 THEN 'UPGRADE' ELSE 'ROLLBACK' END,
                    now()-interval '40 days'+n*interval '1 second',now()-interval '40 days'+n*interval '1 second'
                FROM generate_series(1,?) n
                """,f.tenant(),f.project(),f.device(),f.versionA(),f.versionB(),f.versionB(),f.versionA(),histories);
        return f;
    }

    /** @param f 项目 @return 完整转换历史快照 */
    private static String historySnapshot(Fixture f) {
        return owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY id),'[]'::jsonb)::text FROM public.dev_device_model_binding_history h WHERE project_id=?",String.class,f.project());
    }

    /** @param f 项目 @return 真实历史行数 */
    private long count(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM public.dev_device_model_binding_history WHERE project_id=?",Long.class,f.project());
    }

    /** @param table 固定本域父表 @param id 真实主键 @return 全字段快照 */
    private String entity(String table,UUID id) {
        return owner.queryForObject("SELECT to_jsonb(d)::text FROM public."+table+" d WHERE id=?",String.class,id);
    }

    /** @param f 已确权项目范围，事务结束恢复 */
    private void scope(Fixture f) {
        app.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
        app.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
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

    /** 版本和设备均有持久父关系，同租户邻居显式共用tenant以证明清理没有扩大范围。 */
    private record Fixture(UUID tenant,UUID project,UUID type,UUID device,UUID versionA,UUID versionB) { }
}
