package com.things.link.bootstrap.device.config;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.ModbusConfigService;
import com.things.link.device.application.ModbusPollScheduler;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.infrastructure.DeviceConfigKafkaConsumer;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** ADR0071：真实配置版本、项目锁与旧Kafka信封的发送准入验收。 */
@Import(DeviceConfigDispatchProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"CONFIG_POSTGRES"})
class DeviceConfigDispatchProjectLifecycleTests extends AbstractIntegrationTest {
    /** 独占数据库避免全局Outbox和调度领取其他测试的配置。 */
    private static final String DATABASE_NAME="config_dispatch_lifecycle_"+UUID.randomUUID().toString().replace("-","");
    /** 角色属于集群，使用同一迁移镜像的新数据库。 */
    private static final PostgreSQLContainer<?> CONFIG_POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** APP、Flyway和owner统一实际落点。 */
    private static final String DATABASE_URL=startDatabase();
    /** 关闭无关启动配额。 */ @MockitoBean(enforceOverride=true,name="relaxRestQuota") private ApplicationRunner unusedQuota;
    /** 关闭配置生成的轮询调度。 */ @MockitoBean(enforceOverride=true) private ModbusPollScheduler unusedPoll;
    /** 关闭无关命令扫描。 */ @MockitoBean(enforceOverride=true) private DeviceCommandTimeoutScanner unusedCommands;
    /** 关闭无关任务扫描。 */ @MockitoBean(enforceOverride=true) private TaskSchedulingScanner unusedTasks;
    /** 关闭无关通知领取。 */ @MockitoBean(enforceOverride=true) private NotificationWorkCoordinator unusedNotifications;
    /** 关闭无关聚合回补。 */ @MockitoBean(enforceOverride=true) private PropertyAggregateBackfillScanner unusedBackfill;
    /** 唯一外部替身，只证明HTTP发布端口被调用。 */ @MockitoBean(enforceOverride=true) private CommandDownlinkPublisher publisher;
    /** 真实配置Outbox创建入口。 */ @Autowired private ModbusConfigService configs;
    /** 真实Kafka配置消费者。 */ @Autowired private DeviceConfigKafkaConsumer consumer;
    /** 真实OWNER删除入口。 */ @Autowired private ProjectService projects;
    /** 生命周期SQL保持真实，仅插入锁边界观察。 */ @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** APP数据连接。 */ @Autowired private JdbcTemplate jdbc;
    /** 原事务管理器。 */ @Autowired private PlatformTransactionManager transactionManager;
    /** 原信封反序列化器。 */ @Autowired private ObjectMapper mapper;
    /** 每例独立完整祖先。 */ private final Fixture fixture=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());
    /** 外发边界调用数。 */ private final AtomicInteger publications=new AtomicInteger();
    /** 真实原Outbox信封。 */ private DeviceConfigPush push;
    /** 许可SQL之前的测试观察。 */ private Runnable beforePermit=()->{};
    /** 许可SQL之后、提交之前的测试观察。 */ private Runnable afterPermit=()->{};

    /** 每例真实生成一条全量version=1配置Outbox，再安装准入观察器。 */
    @BeforeEach void prepare() throws Exception {
        verifyIsolation(); seed();
        RlsScopeContext.set(new RlsScope(fixture.tenantId(),fixture.projectId()));
        try {new TransactionTemplate(transactionManager).executeWithoutResult(ignored->configs.pushConfig(fixture.projectId(),fixture.gatewayId()));}
        finally {RlsScopeContext.clear();}
        try(Connection owner=owner()) {
            String payload=text(owner,"SELECT payload FROM sys_outbox_event WHERE project_id=? AND aggregate_id=? AND event_type='DEVICE_CONFIG_PUSH'",fixture.projectId(),fixture.gatewayId());
            push=mapper.readValue(payload,DeviceConfigPush.class);
            assertThat(push.version()).isEqualTo(1); assertThat(push.points()).hasSize(2);
        }
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat((DeviceConfigPush) invocation.getArgument(0)).isEqualTo(push); publications.incrementAndGet(); return Instant.now();})
                .when(publisher).publishConfig(any(DeviceConfigPush.class), any(com.things.link.device.application.DeviceMqttDownlinkRoute.class));
        ProjectLifecycleAccessService lifecycleTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation->{beforePermit.run(); Object result=invocation.callRealMethod(); if(Boolean.TRUE.equals(result)) afterPermit.run(); return result;})
                .when(lifecycleTarget).lockActiveForWrite(any(UUID.class),any(UUID.class));
    }

    /** 已提交许可保持旧代次，下一次重放重新冻结新代次，不在发布时升级旧路由。 */
    @Test void committedRouteIsNotUpgradedByLaterConfiguration() throws Exception {
        try(Connection owner=owner()) {execute(owner,"INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,config_version) VALUES(?,?,?,'MQTT',7)",fixture.gatewayId(),fixture.tenantId(),fixture.projectId());}
        var versions=new java.util.ArrayList<Long>();
        doAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var route=(com.things.link.device.application.DeviceMqttDownlinkRoute)invocation.getArgument(1);
            try(Connection owner=owner()) {execute(owner,"UPDATE dev_access_binding SET config_version=8 WHERE device_id=?",fixture.gatewayId());}
            assertThat(route.deviceId()).isEqualTo(fixture.gatewayId());
            assertThat(route.projectKey()).isEqualTo(push.projectKey());
            assertThat(route.deviceKey()).isEqualTo(push.gatewayKey());
            versions.add(route.configVersion());
            return Instant.now();
        }).when(publisher).publishConfig(any(DeviceConfigPush.class),any(com.things.link.device.application.DeviceMqttDownlinkRoute.class));
        consume(push);consume(push);
        assertThat(versions).containsExactly(7L,8L);
    }

    /** 原Outbox真实存在也不能覆盖当前设备协议、启用或删除拒绝。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"HTTP","COAP","TCP","DISABLED","DELETED"})
    void currentGatewayPermissionPreventsOldConfigDelivery(String boundary) throws Exception {
        try(Connection owner=owner()) {
            if(boundary.equals("DELETED")) execute(owner,"UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id=?",fixture.gatewayId());
            else execute(owner,"INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled) VALUES(?,?,?,?,?)",
                    fixture.gatewayId(),fixture.tenantId(),fixture.projectId(),boundary.equals("DISABLED")?"MQTT":boundary,!boundary.equals("DISABLED"));
        }
        assertThat(catchThrowable(()->consume(push))).isInstanceOf(InvalidDownlinkMessageException.class);
        assertThat(publications).hasValue(0);
    }

    /** ACTIVE每次Kafka重放都调用publisher；平台不伪造比网关version更强的去重。 */
    @Test void activeReplayStillPublishesSameVersion() {consume(push); consume(push); assertThat(publications).hasValue(2);}

    /** 归档及真实删除均永久拒绝旧配置，不进入HTTP边界。 */
    @ParameterizedTest @EnumSource(FrozenState.class)
    void frozenProjectRejectsOldPush(FrozenState state) throws Exception {
        freeze(state); Throwable failure=catchThrowable(()->consume(push));
        assertThat(failure).isInstanceOf(InvalidDownlinkMessageException.class); assertThat(publications).hasValue(0);
    }

    /** tenant、gateway、projectKey、points和version任何篡改都不能借合法项目外发。 */
    @ParameterizedTest @EnumSource(Tamper.class)
    void immutableEnvelopeTamperingIsPermanent(Tamper field) {
        DeviceConfigPush altered=tamper(field);
        Throwable failure=catchThrowable(()->consume(altered));
        assertThat(failure).isInstanceOf(InvalidDownlinkMessageException.class); assertThat(publications).hasValue(0);
    }

    /** 缺少等价Outbox属于不可恢复信封损坏。 */
    @Test void missingEquivalentOutboxIsPermanent() throws Exception {
        try(Connection owner=owner()){execute(owner,"DELETE FROM sys_outbox_event WHERE project_id=? AND aggregate_id=?",fixture.projectId(),fixture.gatewayId());}
        assertThat(catchThrowable(()->consume(push))).isInstanceOf(InvalidDownlinkMessageException.class); assertThat(publications).hasValue(0);
    }

    /** 许可先得时归档等待提交；本次已获准的旧配置仍可外发。 */
    @Test void permitFirstBlocksArchiveUntilAdmissionCommit() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor(); CompletableFuture<Integer> waiterPid=new CompletableFuture<>(); CompletableFuture<Future<?>> waiter=new CompletableFuture<>();
        afterPermit=()->{int holder=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class); Future<?> future=executor.submit(()->{try(Connection owner=owner()){waiterPid.complete(pid(owner)); execute(owner,"UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId());}catch(Exception e){throw new IllegalStateException(e);}}); waiter.complete(future); try{blocked(waiterPid.get(3,TimeUnit.SECONDS),holder,future);}catch(Exception e){throw new IllegalStateException(e);}};
        try {consume(push); waiter.get(3,TimeUnit.SECONDS).get(5,TimeUnit.SECONDS); assertThat(publications).hasValue(1);} finally {finish(executor);}
    }

    /** 冻结事务在先时准入真实等待，提交后重读并永久拒绝。 */
    @Test void freezeFirstMakesAdmissionWaitAndReject() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor(); CompletableFuture<Integer> consumerPid=new CompletableFuture<>(); beforePermit=()->consumerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
        try(Connection owner=owner()){owner.setAutoCommit(false); execute(owner,"UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId()); Future<Throwable> future=executor.submit(()->catchThrowable(()->consume(push)));
            try{blocked(consumerPid.get(3,TimeUnit.SECONDS),pid(owner),future); owner.commit(); assertThat(future.get(5,TimeUnit.SECONDS)).isInstanceOf(InvalidDownlinkMessageException.class); assertThat(publications).hasValue(0);}finally{owner.rollback();}}
        finally {finish(executor);}
    }

    /** 延迟约束在真实COMMIT失败，publisher零调用；移除故障后原消息恢复。 */
    @Test void admissionCommitFailurePreventsExternalCall() throws Exception {
        try(Connection owner=owner()){execute(owner,"CREATE FUNCTION config_dispatch_commit_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+fixture.projectId()+"'::uuid THEN RAISE EXCEPTION 'config commit probe' USING ERRCODE='23514'; END IF; RETURN NEW; END $$"); execute(owner,"CREATE CONSTRAINT TRIGGER config_dispatch_commit_probe AFTER UPDATE ON sys_project DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION config_dispatch_commit_probe()");}
        afterPermit=()->assertThat(jdbc.update("UPDATE sys_project SET name=name WHERE id=?",fixture.projectId())).isEqualTo(1);
        try {Throwable failure=catchThrowable(()->consume(push)); assertThat(sqlState(failure)).isEqualTo("23514"); assertThat(publications).hasValue(0);}
        finally {afterPermit=()->{}; try(Connection owner=owner()){execute(owner,"DROP TRIGGER config_dispatch_commit_probe ON sys_project"); execute(owner,"DROP FUNCTION config_dispatch_commit_probe()");}}
        consume(push); assertThat(publications).hasValue(1);
    }

    /** 真实57014属于基础设施失败，不得包装为永久下行错误；解除锁后恢复。 */
    @Test void projectLockTimeoutRemainsRetryable() throws Exception {
        beforePermit=()->jdbc.execute("SET LOCAL statement_timeout='100ms'");
        try(Connection owner=owner()){owner.setAutoCommit(false); execute(owner,"UPDATE sys_project SET name=name WHERE id=?",fixture.projectId()); Throwable failure=catchThrowable(()->consume(push)); assertThat(sqlState(failure)).isEqualTo("57014"); assertThat(failure).isNotInstanceOf(InvalidDownlinkMessageException.class); assertThat(publications).hasValue(0); owner.rollback();}
        beforePermit=()->{}; consume(push); assertThat(publications).hasValue(1);
    }

    /** 调用真实consumer，明确此类不证明实际MQTT送达。 */
    private void consume(DeviceConfigPush value) {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); consumer.consume(new ConsumerRecord<>(DeviceConfigKafkaConsumer.CONFIG_TOPIC,0,0L,value.gatewayId().toString(),value));}

    /** 使用真实项目服务提交删除，归档则由owner提交权威状态。 */
    private void freeze(FrozenState state) throws Exception {if(state==FrozenState.ARCHIVED){try(Connection owner=owner()){execute(owner,"UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId());}}else{TenantContext.set(new TenantScope(fixture.tenantId(),fixture.projectId(),fixture.accountId())); try{projects.delete(fixture.projectId());}finally{TenantContext.clear();}}}

    /** 构造仍满足record结构约束但不匹配持久全量版本的信封。 */
    private DeviceConfigPush tamper(Tamper field) {List<DeviceConfigPush.Point> points=new ArrayList<>(push.points()); if(field==Tamper.POINTS) points.removeLast(); return new DeviceConfigPush(field==Tamper.TENANT?Uuid7.generate():push.tenantId(),push.projectId(),field==Tamper.GATEWAY?fixture.subDeviceId():push.gatewayId(),push.projectKey()+(field==Tamper.PROJECT_KEY?"_forged":""),push.gatewayKey(),push.configType(),push.version()+(field==Tamper.VERSION?1:0),points);}

    /** 播种完整OWNER、项目、网关、子设备和两个已发布点位。 */
    private void seed() throws Exception {try(Connection owner=owner()){owner.setAutoCommit(false);
        execute(owner,"INSERT INTO sys_tenant(id,name) VALUES (?,'配置准入租户')",fixture.tenantId());
        execute(owner,"INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','配置OWNER')",fixture.accountId(),fixture.accountId()+"@example.com");
        execute(owner,"INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),fixture.tenantId(),fixture.accountId());
        execute(owner,"INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'配置准入项目','sh-1',?)",fixture.projectId(),fixture.tenantId(),fixture.projectKey());
        execute(owner,"INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),fixture.projectId(),fixture.accountId());
        execute(owner,"INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'config_gateway_type','配置网关类型','MODBUS_RTU_CLOUD_GATEWAY','GATEWAY','PUBLISHED'),(?,?,?,'config_sub_type','配置子类型','STANDARD','SUB_DEVICE','PUBLISHED')",fixture.gatewayType(),fixture.tenantId(),fixture.projectId(),fixture.subType(),fixture.tenantId(),fixture.projectId());
        execute(owner,"INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'config_gateway','配置网关','ONLINE'),(?,?,?,?,'config_sub','配置子设备','ONLINE')",fixture.gatewayId(),fixture.tenantId(),fixture.projectId(),fixture.gatewayType(),fixture.subDeviceId(),fixture.tenantId(),fixture.projectId(),fixture.subType());
        for(int index=0;index<2;index++) execute(owner,"INSERT INTO dev_modbus_point_mapping(id,tenant_id,project_id,device_id,sub_device_id,property_key,slave_address,function_code,register_address,data_type,byte_order,scale,\"offset\",polling_interval_ms,version,status) VALUES (?,?,?,?,?,?,?,'READ_HOLDING_REGISTERS',?,'FLOAT32','LITTLE_ENDIAN',?,?,600000,1,'PUBLISHED')",Uuid7.generate(),fixture.tenantId(),fixture.projectId(),fixture.gatewayId(),fixture.subDeviceId(),"temperature_"+index,index+1,100+index*4,new BigDecimal("1.25"),new BigDecimal("-3.50"));
        owner.commit();}}

    /** 核验专库与真实Bean，替身仅限外部端口和自动入口。 */
    private void verifyIsolation(){assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl()); assertThat(AopUtils.isAopProxy(configs)).isTrue(); assertThat(mockingDetails(consumer).isMock()).isFalse(); assertThat(mockingDetails(publisher).isMock()).isTrue(); for(Object bean:List.of(unusedQuota,unusedPoll,unusedCommands,unusedTasks,unusedNotifications,unusedBackfill))assertThat(mockingDetails(bean).isMock()).isTrue(); assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(DATABASE_NAME); assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);}

    /** 观察真实PostgreSQL锁等待，线程提前结束不能冒充阻塞证据。 */
    private void blocked(int waiter,int holder,Future<?> worker)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); try(Connection owner=owner()){while(System.nanoTime()<deadline){assertThat(worker.isDone()).isFalse(); if("true".equals(text(owner,"SELECT (?=ANY(pg_blocking_pids(?)))::text",holder,waiter)))return; Thread.sleep(10);}} throw new AssertionError("未观察到项目锁等待");}

    /** 有界回收工作线程。 */
    private static void finish(ExecutorService executor)throws InterruptedException{executor.shutdown(); if(!executor.awaitTermination(6,TimeUnit.SECONDS)){executor.shutdownNow(); assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}}
    /** 返回owner会话pid。 */ private int pid(Connection owner)throws Exception{return Integer.parseInt(text(owner,"SELECT pg_backend_pid()::text"));}
    /** owner连接固定到专库。 */ private Connection owner()throws SQLException{return DriverManager.getConnection(DATABASE_URL,CONFIG_POSTGRES.getUsername(),CONFIG_POSTGRES.getPassword());}
    /** 参数化写SQL。 */ private void execute(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){p.setQueryTimeout(5); for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]); p.executeUpdate();}}
    /** 单值SQL读取。 */ private String text(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){p.setQueryTimeout(5); for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]); try(ResultSet r=p.executeQuery()){assertThat(r.next()).isTrue(); return r.getString(1);}}}
    /** 提取真实JDBC SQLSTATE。 */ private static String sqlState(Throwable failure){for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql&&sql.getSQLState()!=null)return sql.getSQLState(); return null;}
    /** 启动独占数据库。 */ private static String startDatabase(){CONFIG_POSTGRES.start(); return CONFIG_POSTGRES.getJdbcUrl();}
    /** 清理线程范围；物理库交OwnedTestContainers在本类上下文物理关闭后回收。 */ @AfterEach void clearContext(){TenantContext.clear(); RlsScopeContext.clear();}

    /** 独占数据源覆盖。 */
    @TestConfiguration(proxyBeanMethods=false) static class IsolatedDatabaseConfiguration {
        /** 确保APP、Flyway同库且无后台消费者。 */ @Bean DynamicPropertyRegistrar isolatedDatabaseProperties(){return registry->{registry.add("spring.datasource.url",()->DATABASE_URL); registry.add("spring.flyway.url",()->DATABASE_URL); registry.add("things-link.outbox.publisher.enabled",()->"false"); registry.add("things-link.notification.retry.enabled",()->"false"); registry.add("spring.kafka.listener.auto-startup",()->"false");};}
    }
    /** 两种冻结事实。 */ private enum FrozenState{/** 可恢复归档。 */ARCHIVED,/** 真实软删除。 */DELETED}
    /** 不可变信封篡改轴。 */ private enum Tamper{/** 租户。 */TENANT,/** 网关。 */GATEWAY,/** 项目键。 */PROJECT_KEY,/** 点位集。 */POINTS,/** 版本。 */VERSION}
    /** 真实祖先ID。 */ private record Fixture(UUID tenantId,UUID projectId,UUID accountId,UUID gatewayType,UUID subType,UUID gatewayId,UUID subDeviceId){/** 稳定项目键。 */ String projectKey(){return "config_probe_"+projectId.toString().replace("-","");}}
}
