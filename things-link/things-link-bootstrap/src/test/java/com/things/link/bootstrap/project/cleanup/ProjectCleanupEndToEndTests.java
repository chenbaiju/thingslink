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
import com.things.link.dashboard.application.DashboardProjectCleanupContributor;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import com.things.link.device.application.DeviceProjectCleanupContributor;
import com.things.link.iam.application.IamProjectCleanupContributor;
import com.things.link.iam.infrastructure.persistence.JdbcIamProjectCleanupRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.things.link.project.application.SupportProjectCleanupContributor;
import com.things.link.project.application.ProjectMemberCleanupContributor;
import com.things.link.project.application.ProjectCleanupWorker;
import com.things.link.bootstrap.fixture.ProjectExportMinioFixture;
import com.things.link.export.application.ProjectExportCleanupWorker;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportJobRepository;
import com.things.link.support.storage.MinioPrivateObjectStorage;
import com.things.link.support.storage.PrivateObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.HashSet;
import com.things.link.support.tenant.DatabaseWorkloadAspect;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import com.things.link.project.application.ProjectCleanupFinalizationContributor;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.cleanup.SupportProjectCleanupService;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-P0-5h11c：同一项目的非空跨域事实、真实对象和共享压缩chunk整合资格的真实资格。 */
@Testcontainers
class ProjectCleanupEndToEndTests {
    /** 各域代表事实的固定观察集合；所有表都有项目轴，保留审计/计量另行核对。 */
    private static final List<String> TABLES=List.of("sys_project_export_job","sys_project_export_upload_cleanup",
            "task_target","task_execution","task_schedule","task_job","rule_execution_log","rule_version","rule_message",
            "alarm_notification_read","alarm_event","alarm_instance","alarm_rule","app_device_bind_token","app_refresh_token","app_user_device","app_user_role",
            "ts_property_point_internal","ts_property_point_1m_internal","ts_property_point_1h_internal","ts_property_point_1d_internal",
            "ts_device_command_attempt","ts_device_command","ts_device_message_log","sys_inbox_message","sys_message_log_inbox",
            "dev_command_definition","dev_device","dev_type","sys_refresh_token","sys_outbox_event","sys_idempotency_record","sys_project_member");
    /** 固定三级聚合，刷新/压缩只准备隔离夹具，不由被测worker重算邻居。 */
    private static final List<String> AGGREGATES=List.of("ts_property_point_1m_internal","ts_property_point_1h_internal","ts_property_point_1d_internal");
    /** 独占完整迁移库，使用与前序设备/遥测相同的实际数据库版本。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cleanup_end_to_end").withUsername("thingslink").withPassword("thingslink");
    /** 迁移与全字段观察。 */
    private static JdbcTemplate owner;
    private static HikariDataSource ownerSource;
    private static HikariDataSource appSource;
    /** 实际APP权限执行被测入口。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** project持久事实。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实准入、锁及租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 已完成的十个前置领域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 被测project成员受限入口。 */
    private ProjectMemberCleanupContributor facts;
    /** 与墓碑条件更新同一事务的最终贡献。 */
    private ProjectCleanupFinalizationContributor finalization;
    /** 真实成员贡献及项目原事务。 */
    private ProjectCleanupBatchService batches;

    /** 所有正式领域迁移与贡献器齐备，不用伪造零行步骤做运行恢复验收。 */
    @BeforeAll
    static void migrate() {
        ownerSource = source("thingslink", "cleanup-end-to-end-owner");
        owner = new JdbcTemplate(ownerSource);
        flyway("20260906.0110").migrate();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        appSource = source("thingslink_app", "cleanup-end-to-end-app");
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
        // 本测试中长连接预编译查询曾读到过期的压缩 chunk 行数；仅观察池禁用。
        if (user.equals("thingslink")) config.addDataSourceProperty("prepareThreshold", "0");
        return new HikariDataSource(config);
    }

    /** 隔离库每例重置技术事实，保留不可变审计；不操作开发库。 */
    @BeforeEach
    void prepare() {
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
                proxy(new DashboardProjectCleanupContributor(new JdbcDashboardProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner),
                proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app))),
                proxy(new DeviceProjectCleanupContributor(new JdbcDeviceProjectCleanupRepository(app))),
                proxy(new IamProjectCleanupContributor(new JdbcIamProjectCleanupRepository(app))),
                proxy(new SupportProjectCleanupContributor(proxy(new SupportProjectCleanupService(app)))));
        facts = proxy(new ProjectMemberCleanupContributor(projects));
        finalization = proxy(new ProjectCleanupFinalizationContributor(admission,projects,new AuditLogService(app,new ObjectMapper())));
        List<ProjectCleanupContributor> contributors = new ArrayList<>(prerequisites);
        contributors.add(facts); contributors.add(finalization);
        batches = proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** 真对象先收束，各非空域顺序删除；期间实例退出可接管，邻居压缩历史/身份/账单逐轮保持。 */
    @Test
    void cleansCrossDomainProjectWithoutLosingNeighborsOrRetainedEvidence() throws Exception {
        Fixture first=fixture(false,null); Fixture neighbor=fixture(true,first); Fixture other=fixture(true,null);
        seedDomains(first,1001); seedDomains(neighbor,1); seedDomains(other,1);
        seedMembers(first,1000);
        Instant start=owner.queryForObject("SELECT date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'-interval '40 days'",Timestamp.class).toInstant();
        for (String aggregate:AGGREGATES) owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz,true)","public."+aggregate,Timestamp.from(start),Timestamp.from(start.plusSeconds(86400)));
        owner.queryForList("SELECT compress_chunk(c,if_not_compressed=>true) FROM show_chunks('public.ts_property_point_internal') c");
        assertThat(owner.queryForObject("SELECT count(*) FROM timescaledb_information.chunks WHERE hypertable_name='ts_property_point_internal' AND is_compressed",Long.class)).isPositive();
        String prefix="projects/end-to-end/"+UUID.randomUUID()+"/";
        String key=prefix+"owned.zip"; String neighborKey=prefix+"neighbor.zip";
        PrivateObjectStorage storage=new MinioPrivateObjectStorage(ProjectExportMinioFixture.client(),ProjectExportMinioFixture.client());
        Path file=Files.createTempFile("project-cleanup-export-",".zip"); byte[] bytes="独占导出对象夹具".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.write(file,bytes); storage.upload("export",key,file,"application/zip",Map.of()); storage.upload("export",neighborKey,file,"application/zip",Map.of());
            UUID upload=seedExport(first,key,bytes);
            String neighboring=snapshot(neighbor); String otherBefore=snapshot(other); String shared=shared(first);
            assertThat(tenantDevices(neighbor)).isEqualTo(2); assertThat(tenantDevices(other)).isEqualTo(1);
            ProjectCleanupWorker worker=worker(admission,batches);
            worker.cleanNextBatch();
            assertThat(owner.queryForObject("SELECT cleanup_failure_code FROM public.sys_project WHERE id=?",String.class,first.project())).isEqualTo("EXPORT_NOT_TERMINAL");
            assertThat(total(first)).isGreaterThan(2000);
            JdbcProjectExportJobRepository jobs=proxy(new JdbcProjectExportJobRepository(app));
            assertThat(jobs.claimExpired()).isEmpty();
            assertThat(ProjectExportMinioFixture.readObject(key)).isEqualTo(bytes);
            owner.update("UPDATE public.sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE upload_id=?",upload);
            new ProjectExportCleanupWorker(jobs,storage).cleanupExpired(jobs.claimExpired().orElseThrow());
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).containsExactly(neighborKey);
            due(first);
            long expectedRows=total(first);
            Set<String> visited=new HashSet<>(); boolean interrupted=false; long deleted=0;
            for (int i=0;i<100;i++) {
                String stage=stage(first); if (stage.equals("DONE")) break; visited.add(stage);
                if (stage.equals("RULE") && !interrupted) {
                    ProjectCleanupClaim abandoned=admission.claimNext().orElseThrow(); String held=projectSnapshot(first);
                    worker.cleanNextBatch(); assertThat(projectSnapshot(first)).isEqualTo(held);
                    owner.update("UPDATE public.sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.project());
                    worker=worker(admission,batches); long before=total(first); long counter=rows(first);
                    worker.cleanNextBatch(); long removed=before-total(first);
                    assertThat(removed).as("清理阶段=%s，单批删除行数", stage).isBetween(0L,500L); assertThat(rows(first)-counter).isEqualTo(removed); deleted+=removed;
                    assertThat(batches.execute(abandoned)).isEmpty(); interrupted=true;
                } else {
                    long before=total(first); long counter=rows(first);
                    worker.cleanNextBatch(); long removed=before-total(first);
                    assertThat(removed).as("清理阶段=%s，单批删除行数", stage).isBetween(0L,500L);
                    assertThat(rows(first)-counter).isEqualTo(removed); deleted+=removed;
                }
                String blocked=owner.queryForObject("SELECT cleanup_failure_code FROM public.sys_project WHERE id=?",String.class,first.project());
                assertThat(blocked).as("阶段%s不得跳过未解决的失败",stage).isNull();
                assertThat(snapshot(neighbor)).isEqualTo(neighboring); assertThat(snapshot(other)).isEqualTo(otherBefore);
                assertThat(shared(first)).isEqualTo(shared);
                // 可信邻居的租户存量在项目冻结后仍计入其设备，直到DEVICE真实删除才下降。
                long remainingDevices=owner.queryForObject("SELECT count(*) FROM public.dev_device WHERE project_id=?",Long.class,first.project());
                assertThat(tenantDevices(neighbor)).isEqualTo(1+remainingDevices);
                assertThat(ProjectExportMinioFixture.readObject(neighborKey)).isEqualTo(bytes);
            }
            assertThat(visited).containsExactlyInAnyOrder(java.util.Arrays.stream(ProjectCleanupStage.values()).map(Enum::name).toArray(String[]::new));
            assertThat(interrupted).isTrue(); assertThat(stage(first)).isEqualTo("DONE"); assertThat(total(first)).isZero();
            assertThat(rows(first)).isEqualTo(deleted); assertThat(deleted).isEqualTo(expectedRows);
            System.out.println("项目清理全链：13阶段（历史OTA为空）、33代表表、实际删除="+deleted+"，邻居/计量/历史审计保持，失联接管通过");
            assertThat(tenantDevices(neighbor)).isEqualTo(1); assertThat(tenantDevices(other)).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT status='PURGED' AND name='已清理项目' AND timezone='UTC' FROM public.sys_project WHERE id=?",Boolean.class,first.project())).isTrue();
            assertThat(owner.queryForObject("SELECT count(*) FROM public.sys_audit_log WHERE project_id=? AND action='project.cleanup.started'",Long.class,first.project())).isEqualTo(1);
            assertThat(owner.queryForObject("SELECT count(*) FROM public.sys_audit_log WHERE project_id=? AND action='project.cleanup.completed'",Long.class,first.project())).isEqualTo(1);
            String done=projectSnapshot(first); worker.cleanNextBatch(); assertThat(projectSnapshot(first)).isEqualTo(done);
        } finally { ProjectExportMinioFixture.deletePrefix(prefix); Files.deleteIfExists(file); }
    }

    /** @param entry 真实领取 @param service 真实或故障包装的批次 @return 带实际DATA路由切面的worker */
    private ProjectCleanupWorker worker(ProjectCleanupAdmissionService entry,ProjectCleanupBatchService service) {
        AspectJProxyFactory factory=new AspectJProxyFactory(new ProjectCleanupWorker(entry,service));
        factory.addAspect(new DatabaseWorkloadAspect()); return factory.getProxy();
    }
    /** @param active 邻居或到期项目 @param shared 同租户共享账号及App用户 @return 真实跨域父实体 */
    private static Fixture fixture(boolean active,Fixture shared) {
        Fixture f=new Fixture(shared==null?UUID.randomUUID():shared.tenant(),UUID.randomUUID(),shared==null?UUID.randomUUID():shared.account(),UUID.randomUUID(),UUID.randomUUID(),shared==null?UUID.randomUUID():shared.user());
        if (shared==null) {
            owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'全链清理租户')",f.tenant());
            owner.update("INSERT INTO public.sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','共享账号')",f.account(),f.account()+"@example.test");
            owner.update("INSERT INTO public.sys_tenant_member(id,tenant_id,account_id) VALUES (gen_random_uuid(),?,?)",f.tenant(),f.account());
            owner.update("INSERT INTO public.app_user(id,tenant_id,username,password_hash,display_name) VALUES (?,?,'shared-user','hash','共享用户')",f.user(),f.tenant());
            owner.update("INSERT INTO public.sys_tenant_work_slot(work_type,tenant_id,lease_token,leased_until) VALUES ('NOTIFICATION',?,gen_random_uuid(),now()+interval '1 minute')",f.tenant());
        }
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'全链项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",f.project(),f.tenant(),"e2e_"+f.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        owner.update("INSERT INTO public.sys_project_member(id,project_id,account_id,role) VALUES (gen_random_uuid(),?,?,'OWNER')",f.project(),f.account());
        owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'type','类型','STANDARD','DIRECT','DRAFT')",f.type(),f.tenant(),f.project());
        owner.update("INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,'device','设备')",f.device(),f.tenant(),f.project(),f.type());
        return f;
    }
    /** @param f 项目 @param rawCount 同日共享chunk内的真实raw行数，其余每个代表表至少一行 */
    private static void seedDomains(Fixture f,int rawCount) {
        UUID job=UUID.randomUUID(), execution=UUID.randomUUID(), rule=UUID.randomUUID(), version=UUID.randomUUID();
        UUID alarm=UUID.randomUUID(), instance=UUID.randomUUID(), definition=UUID.randomUUID(), command=UUID.randomUUID();
        owner.update("INSERT INTO public.task_job(id,tenant_id,project_id,name,status,target_type,command_key,input,created_by,created_at,updated_at) VALUES (?,?,?,'任务','ACTIVE','ALL_DEVICES','reboot','{}',?,now(),now())",job,f.tenant(),f.project(),f.account());
        owner.update("INSERT INTO public.task_schedule(id,tenant_id,project_id,job_id,schedule_type,run_at,timezone,created_at,updated_at) VALUES (gen_random_uuid(),?,?,?,'ONCE',now(),'UTC',now(),now())",f.tenant(),f.project(),job);
        owner.update("INSERT INTO public.task_execution(id,tenant_id,project_id,job_id,target_type,command_key,input,requested_by,trigger_type,status,started_at,created_at,updated_at) VALUES (?,?,?,?,'ALL_DEVICES','reboot','{}',?,'MANUAL','EXPANDING',now(),now(),now())",execution,f.tenant(),f.project(),job,f.account());
        owner.update("INSERT INTO public.task_target(execution_id,tenant_id,project_id,device_id,status) VALUES (?,?,?,?,'PENDING')",execution,f.tenant(),f.project(),f.device());
        owner.update("INSERT INTO public.rule_message(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?,?,?,'规则',?,now(),now())",rule,f.tenant(),f.project(),f.account());
        owner.update("INSERT INTO public.rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,created_by,created_at) VALUES (?,?,?,?,1,'input => input',repeat('a',64),?,now())",version,f.tenant(),f.project(),rule,f.account());
        owner.update("UPDATE public.rule_message SET status='ACTIVE',active_version_id=? WHERE id=?",version,rule);
        owner.update("INSERT INTO public.rule_execution_log(id,tenant_id,project_id,message_id,rule_id,rule_version_id,attempt,status,result_code,duration_millis,input_bytes,output_bytes,created_at) VALUES (gen_random_uuid(),?,?,gen_random_uuid(),?,?,1,'SUCCESS','SUCCESS',1,1,1,now()-interval '40 days')",f.tenant(),f.project(),rule,version);
        owner.update("INSERT INTO public.alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?,?,?,'规则','HIGH_TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')",alarm,f.tenant(),f.project(),f.device());
        owner.update("INSERT INTO public.alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,first_condition_at,last_received_at,last_value,created_at,updated_at) VALUES (?,?,?,?,'DEVICE',?,'HIGH_TEMPERATURE','MAJOR','ACTIVE',now()-interval '40 days',now()-interval '40 days',31,now()-interval '40 days',now()-interval '40 days')",instance,f.tenant(),f.project(),alarm,f.device());
        owner.update("INSERT INTO public.alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,received_at,condition_state,ack_state) VALUES (gen_random_uuid(),?,?,?,'ACTIVATED',gen_random_uuid(),'trace',now()-interval '40 days','ACTIVE','UNACKNOWLEDGED')",f.tenant(),f.project(),instance);
        // ADR0093：共享账号在各项目独立阅读，回执必须先于事件按真实行数纳入ALARM阶段。
        owner.update("INSERT INTO public.alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id) SELECT gen_random_uuid(),tenant_id,project_id,?,id FROM public.alarm_event WHERE instance_id=?",f.account(),instance);
        owner.update("INSERT INTO public.app_refresh_token(id,tenant_id,project_id,app_user_id,token_hash,family_id,expires_at) VALUES (gen_random_uuid(),?,?,?,digest(gen_random_uuid()::text,'sha256'),gen_random_uuid(),now()+interval '10 days')",f.tenant(),f.project(),f.user());
        owner.update("INSERT INTO public.app_device_bind_token(id,tenant_id,project_id,device_id,token_hash,purpose,target_role,issued_by_app_user_id,expires_at,max_attempts) VALUES (gen_random_uuid(),?,?,?,digest(gen_random_uuid()::text,'sha256'),'CLAIM','PRIMARY',?,now()+interval '1 day',3)",f.tenant(),f.project(),f.device(),f.user());
        owner.update("INSERT INTO public.app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role,status) VALUES (gen_random_uuid(),?,?,?,?,'MEMBER','CLOSED')",f.tenant(),f.project(),f.user(),f.device());
        owner.update("INSERT INTO public.app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (gen_random_uuid(),?,?,?,'OBSERVER')",f.tenant(),f.project(),f.user());
        owner.update("INSERT INTO public.dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?,?,?,?,'reboot','重启','{}','{}',30)",definition,f.tenant(),f.project(),f.type());
        owner.update("INSERT INTO public.ts_device_command(id,tenant_id,project_id,target_device_id,connection_device_id,command_definition_id,command_key,request_payload,status,idempotency_key,requested_by,timeout_seconds,attempt_count,max_attempts,next_attempt_at,deadline_at,trace_id,accepted_at,updated_at,completed_at) VALUES (?,?,?,?,?,?,'reboot','{}','SUCCEEDED',gen_random_uuid()::text,?,30,1,3,NULL,now()-interval '40 days','test',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days')",command,f.tenant(),f.project(),f.device(),f.device(),definition,f.account());
        owner.update("INSERT INTO public.ts_device_command_attempt(id,tenant_id,project_id,command_id,attempt_no,outbox_event_id,connection_device_id,topic,status,deadline_at,created_at,updated_at,completed_at) VALUES (gen_random_uuid(),?,?,?,1,gen_random_uuid(),?,'tc/test','SUCCEEDED',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days')",f.tenant(),f.project(),command,f.device());
        owner.update("INSERT INTO public.ts_device_message_log(id,project_id,device_id,message_id,tenant_id,protocol,direction,ts,received_at,created_at) VALUES (gen_random_uuid(),?,?,gen_random_uuid(),?,'MQTT','UP',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days')",f.project(),f.device(),f.tenant());
        for (String inbox:List.of("sys_inbox_message","sys_message_log_inbox")) owner.update("INSERT INTO public."+inbox+"(message_id,project_id,received_at) VALUES (gen_random_uuid(),?,now()-interval '40 days')",f.project());
        owner.update("INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double) SELECT ?,?,'temperature',date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'-interval '40 days'+interval '1 second'*n,gen_random_uuid(),42 FROM generate_series(1,?) n",f.project(),f.device(),rawCount);
        owner.update("INSERT INTO public.sys_refresh_token(id,account_id,tenant_id,project_id,project_generation,token_hash,family_id,issued_at,expires_at) VALUES (gen_random_uuid(),?,?,?,0,digest(gen_random_uuid()::text,'sha256'),gen_random_uuid(),now()-interval '41 days',now()-interval '40 days')",f.account(),f.tenant(),f.project());
        owner.update("INSERT INTO public.sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,event_type,destination_topic,partition_key,payload,trace_id,status,published_at,created_at) VALUES (gen_random_uuid(),?,?,'DEVICE',?,'DEVICE_COMMAND_DISPATCH','tc.device.downlink',?,'{}','test','PUBLISHED',now()-interval '40 days',now()-interval '40 days')",f.tenant(),f.project(),f.device(),f.device().toString());
        owner.update("INSERT INTO public.sys_idempotency_record(id,tenant_id,project_id,idempotency_key,request_method,request_path,request_body_hash,status,expires_at) VALUES (gen_random_uuid(),?,?,gen_random_uuid()::text,'POST','/test',repeat('a',64),'IN_PROGRESS',now()-interval '1 day')",f.tenant(),f.project());
        owner.update("INSERT INTO public.sys_audit_log(id,tenant_id,project_id,actor_account_id,target_type,target_id,action,trace_id,details,created_at) VALUES (gen_random_uuid(),?,?,?,'project',?,'project.created','test','{}',now()-interval '40 days')",f.tenant(),f.project(),f.account(),f.project());
        owner.update("INSERT INTO public.sys_usage_fact(id,tenant_id,project_id,metric,usage_date,event_key,occurred_at) VALUES (gen_random_uuid(),?,?,'REST_API_CALL',current_date,'retained',now())",f.tenant(),f.project());
        owner.update("INSERT INTO public.sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value) VALUES (gen_random_uuid(),?,?,current_date,'REST_API_CALL',1)",f.tenant(),f.project());
    }
    /** @param f 项目 @param size 额外成员数，账号均属于保留集合 */
    private static void seedMembers(Fixture f,int size) {
        owner.update("WITH accounts AS (INSERT INTO public.sys_account(id,email,password_hash,display_name) SELECT gen_random_uuid(),gen_random_uuid()::text||'@example.test','hash','保留账号' FROM generate_series(1,?) RETURNING id) INSERT INTO public.sys_project_member(id,project_id,account_id,role) SELECT gen_random_uuid(),?,id,'VIEWER' FROM accounts",size,f.project());
    }
    /** @param f 项目 @param key 真对象键 @param bytes 已上传内容 @return 上传身份，签名宽限仍有效 */
    private static UUID seedExport(Fixture f,String key,byte[] bytes) throws Exception {
        UUID job=UUID.randomUUID(),upload=UUID.randomUUID();
        owner.update("INSERT INTO public.sys_project_export_job(id,tenant_id,project_id,project_generation,requester_account_id,status,snapshot_at,current_upload_id,object_key,object_size,object_sha256,succeeded_at,expires_at) VALUES (?,?,?,1,?,'SUCCEEDED',now()-interval '25 hours',?,?,?,?,now()-interval '25 hours',now()-interval '1 second')",job,f.tenant(),f.project(),f.account(),upload,key,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        owner.update("INSERT INTO public.sys_project_export_upload_cleanup(id,export_id,tenant_id,project_id,upload_id,object_key,status,created_at,next_attempt_at) VALUES (gen_random_uuid(),?,?,?,?,?,'ADOPTED',now()-interval '25 hours',now()+interval '5 minutes')",job,f.tenant(),f.project(),upload,key);
        return upload;
    }
    /** @param f 项目 @return 代表事实真实总行数，不把状态改写计成删除 */
    private static long total(Fixture f) {
        long count=0; for (String table:TABLES) count+=owner.queryForObject("SELECT count(*) FROM public."+table+" WHERE project_id=?",Long.class,f.project()); return count;
    }
    /** @param f 邻居 @return 包括压缩raw/聚合在内的稳定全字段快照 */
    private static String snapshot(Fixture f) {
        StringBuilder result=new StringBuilder();
        for (String table:TABLES) result.append(table).append(owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM public."+table+" t WHERE project_id=?",String.class,f.project()));
        return result.toString();
    }
    /** @param f 被清理项目 @return 共享身份、槽及历史账单，准入/完成新增审计另行单独核对 */
    private static String shared(Fixture f) {
        return owner.queryForObject("SELECT jsonb_build_object('accounts',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_account t),'tenantMembers',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_tenant_member t),'users',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.app_user t),'slots',(SELECT jsonb_agg(to_jsonb(t) ORDER BY tenant_id) FROM public.sys_tenant_work_slot t),'historicalAudit',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_audit_log t WHERE action NOT IN ('project.cleanup.started','project.cleanup.completed')),'usage',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_usage_fact t WHERE project_id=?),'daily',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.sys_usage_counter_daily t WHERE project_id=?))::text",String.class,f.project(),f.project());
    }
    /** @param active 仍可访问的同租户邻居，读取真实既有存量投影 @return 整个owner租户设备数 */
    private long tenantDevices(Fixture active) { return app.queryForObject("SELECT tenant_used_value FROM public.project_device_quota_usage(?,?)",Long.class,active.tenant(),active.project()); }
    /** @param f 项目 @return 累计真实删除计数 */
    private static long rows(Fixture f) { return owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?",Long.class,f.project()); }
    /** @param f 项目 @return 当前持久阶段 */
    private static String stage(Fixture f) { return owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?",String.class,f.project()); }
    /** @param f 项目 @return 完整能力快照 */
    private static String projectSnapshot(Fixture f) { return owner.queryForObject("SELECT to_jsonb(p)::text FROM public.sys_project p WHERE id=?",String.class,f.project()); }
    /** @param f 仅取消夹具退避，不缩短对象或重放窗口 */
    private static void due(Fixture f) { owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",f.project()); }
    /** @return 独立受控并发连接 */
    private static Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"); }
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
                        "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/enduser", "classpath:db/migration/export",
                        "classpath:db/migration/dashboard")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }
    /** @param tenant 账单归属 @param project 清理轴 @param account 共享账号 @param type 设备类型 @param device 被跨域引用设备 @param user 租户App用户 */
    private record Fixture(UUID tenant,UUID project,UUID account,UUID type,UUID device,UUID user) { }
}
