package com.things.link.bootstrap.device.config;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.ingestion.infrastructure.DeviceConfigReplyKafkaConsumer;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceConfigReply;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockingDetails;

/** S12-P0-5e4a：真实APP RLS下由原consumer处理配置回执，验证后台范围与项目冻结准入。 */
@Import(DeviceConfigReplyProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"CONFIG_POSTGRES"})
class DeviceConfigReplyProjectLifecycleTests extends AbstractIntegrationTest {
    /** 全局Outbox和自动调度要求物理独占，不能用随机project掩盖共享候选。 */
    private static final String DATABASE_NAME = "config_reply_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 与生产PG/Timescale版本一致的独立实例。 */
    private static final PostgreSQLContainer<?> CONFIG_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Spring、Flyway和owner统一落点。 */
    private static final String DATABASE_URL = startDatabase();
    /** 父runner不得修改共享库配额。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    /** 关闭当前验收不驱动的后台全局领取者，生产consumer与事务代理保持真实。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 任务扫描与配置回执无关。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 命令重试不得触碰测试专库夹具。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 属性回补不是配置ACK合同。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 原Kafka consumer，不能在测试外层恢复范围。 */
    @Autowired private DeviceConfigReplyKafkaConsumer consumer;
    /** APP连接用于确认真实运行角色，不能用于包裹consume。 */
    @Autowired private JdbcTemplate jdbc;
    /** 真实OWNER删除，项目行进入DELETING且历史配置保留。 */
    @Autowired private ProjectService projects;

    /** 无外层上下文地验证正常重推、版本相等/拒绝零写以及归档/删除停止旧ACK。 */
    @ParameterizedTest
    @EnumSource(Case.class)
    void configReplyRestoresTrustedScopeAndHonorsProjectLifecycle(Case scenario) throws Exception {
        Fixture fixture = seed(scenario.currentVersion);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        for (Object mock : List.of(unusedQuotaRunner, unusedNotificationCoordinator, unusedTaskScanner,
                unusedCommandScanner, unusedBackfillScanner)) assertThat(mockingDetails(mock).isMock()).isTrue();
        freeze(fixture, scenario);
        DeviceConfigReply reply = reply(fixture.tenantId(), fixture.projectId(), fixture.gatewayId(),
                scenario.replyVersion, scenario.status);
        consumer.consume(record(reply));
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertRuntimeFacts(fixture, scenario.expectedWrites);
    }

    /** 错tenant/project不能借真实gatewayId读取或写入另一范围，且consumer不得依赖测试ThreadLocal。 */
    @Test
    void rejectsMismatchedTrustedScopeWithoutWrites() throws Exception {
        Fixture fixture = seed(3);
        for (DeviceConfigReply reply : List.of(
                reply(UUID.randomUUID(), fixture.projectId(), fixture.gatewayId(), 2, DeviceConfigReply.Status.APPLIED),
                reply(fixture.tenantId(), UUID.randomUUID(), fixture.gatewayId(), 2, DeviceConfigReply.Status.APPLIED))) {
            assertNoOuterScope();
            consumer.consume(record(reply));
            assertNoOuterScope();
        }
        assertRuntimeFacts(fixture, 0);
    }

    /** Outbox先写而poll同步命中真实23514时整个processReply事务回滚，不能留下半份重推。 */
    @Test
    void rollsBackOutboxWhenPollSqlFails() throws Exception {
        Fixture fixture = seed(3);
        try (Connection owner = owner()) {
            execute(owner, """
                    CREATE FUNCTION config_reply_reject_poll() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'injected poll constraint' USING ERRCODE='23514'; END $$
                    """);
            execute(owner, "CREATE TRIGGER dev_modbus_poll_config_reply_reject BEFORE INSERT ON dev_modbus_poll FOR EACH ROW EXECUTE FUNCTION config_reply_reject_poll()");
        }
        try {
            DeviceConfigReply reply = reply(fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), 2,
                    DeviceConfigReply.Status.APPLIED);
            assertNoOuterScope();
            assertThatThrownBy(() -> consumer.consume(record(reply))).isInstanceOf(RuntimeException.class);
            assertNoOuterScope();
            assertRuntimeFacts(fixture, 0);
        } finally {
            try (Connection owner = owner()) {
                execute(owner, "DROP TRIGGER IF EXISTS dev_modbus_poll_config_reply_reject ON dev_modbus_poll");
                execute(owner, "DROP FUNCTION IF EXISTS config_reply_reject_poll()");
            }
        }
    }

    /** 原consumer记录构造只使用实际网关分区键。 */
    private static ConsumerRecord<String, DeviceConfigReply> record(DeviceConfigReply reply) {
        return new ConsumerRecord<>(DeviceConfigReplyKafkaConsumer.CONFIG_REPLY_TOPIC, 0, 0L,
                reply.gatewayId().toString(), reply);
    }

    /** 构造器本身继续执行公开消息完整性校验，测试不绕过字段合同。 */
    private static DeviceConfigReply reply(UUID tenantId, UUID projectId, UUID gatewayId, int version,
                                           DeviceConfigReply.Status status) {
        return new DeviceConfigReply(Uuid7.generate(), tenantId, projectId, gatewayId,
                DeviceConfigReply.CONFIG_TYPE, version, status,
                status == DeviceConfigReply.Status.REJECTED ? "INVALID_CONFIG" : null,
                status == DeviceConfigReply.Status.REJECTED ? "网关拒绝配置" : null,
                Instant.now(), Instant.now(), "config-reply-lifecycle");
    }

    /** Kafka consumer调用前后都必须没有测试伪造的范围或外层事务。 */
    private static void assertNoOuterScope() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** owner只观察独立提交结果；Outbox和poll必须原子同增或同为零。 */
    private void assertRuntimeFacts(Fixture fixture, long expected) throws SQLException {
        try (Connection owner = owner()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND aggregate_id=? AND event_type='DEVICE_CONFIG_PUSH'", fixture.projectId(), fixture.gatewayId())).isEqualTo(expected);
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id=? AND device_id=?", fixture.projectId(), fixture.gatewayId())).isEqualTo(expected);
        }
    }

    /** 每例真实全链父表：云轮询网关、子设备、属性及已发布映射均满足原仓储约束。 */
    private Fixture seed(int version) throws SQLException {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            execute(c, "INSERT INTO sys_tenant(id,name) VALUES (?, '配置回执租户')", f.tenantId());
            execute(c, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused','配置OWNER')", f.accountId(), f.accountId()+"@example.com");
            execute(c, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", Uuid7.generate(), f.tenantId(), f.accountId());
            execute(c, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'配置回执项目','sh-1',?)", f.projectId(), f.tenantId(), "cfg_"+f.projectId().toString().replace("-", ""));
            execute(c, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", Uuid7.generate(), f.projectId(), f.accountId());
            execute(c, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES (?,?,?,'cfg_gateway','配置网关','GATEWAY','MODBUS_RTU_CLOUD_GATEWAY','WIFI','PUBLISHED'),(?,?,?,'cfg_sub','配置子设备','SUB_DEVICE','STANDARD','WIFI','PUBLISHED')", f.gatewayType(), f.tenantId(), f.projectId(), f.subType(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,'cfg_gateway','配置网关','OFFLINE'),(?,?,?,?,'cfg_sub','配置子设备','OFFLINE')", f.gatewayId(), f.tenantId(), f.projectId(), f.gatewayType(), f.subId(), f.tenantId(), f.projectId(), f.subType());
            execute(c, "INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES (?,?,?,?, 'temperature','温度','REPORT','NUMBER')", Uuid7.generate(), f.tenantId(), f.projectId(), f.subType());
            execute(c, "INSERT INTO dev_modbus_point_mapping(id,tenant_id,project_id,device_id,sub_device_id,property_key,slave_address,function_code,register_address,data_type,byte_order,scale,\"offset\",polling_interval_ms,version,status) VALUES (?,?,?,?,?,'temperature',1,'READ_HOLDING_REGISTERS',100,'FLOAT32','BIG_ENDIAN',1,0,60000,?,'PUBLISHED')", Uuid7.generate(), f.tenantId(), f.projectId(), f.gatewayId(), f.subId(), version);
            c.commit();
        }
        return f;
    }

    /** 状态变更在consumer调用前独立提交；测试线程不携带项目上下文。 */
    private void freeze(Fixture f, Case scenario) throws SQLException {
        if (scenario == Case.ARCHIVED_STALE) try (Connection c = owner()) {
            execute(c, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        } else if (scenario == Case.DELETED_STALE) {
            TenantContext.set(new com.things.link.shared.tenant.TenantScope(f.tenantId(), f.projectId(), f.accountId()));
            try { projects.delete(f.projectId()); } finally { TenantContext.clear(); }
        }
    }

    /** 不删除业务/审计历史，独立容器由OwnedTestContainers在本类上下文物理关闭后回收；只确保线程上下文不泄漏。 */
    @AfterEach void clearContext() { TenantContext.clear(); RlsScopeContext.clear(); }
    /** owner只用于夹具和独立提交后的观察。 */
    private Connection owner() throws SQLException { return DriverManager.getConnection(DATABASE_URL, CONFIG_POSTGRES.getUsername(), CONFIG_POSTGRES.getPassword()); }
    /** 参数化有界写。 */
    private void execute(Connection c,String sql,Object... values) throws SQLException { try(PreparedStatement q=c.prepareStatement(sql)){q.setQueryTimeout(5);for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();} }
    /** 计数查询不得把缺行伪装成零。 */
    private long number(Connection c,String sql,Object... values) throws SQLException { try(PreparedStatement q=c.prepareStatement(sql)){q.setQueryTimeout(5);for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(ResultSet r=q.executeQuery()){assertThat(r.next()).isTrue();return r.getLong(1);}} }
    /** 静态启动必须早于动态属性注册。 */
    private static String startDatabase(){CONFIG_POSTGRES.start();return CONFIG_POSTGRES.getJdbcUrl();}

    /** 独占专库并禁自动Outbox/Kafka启动，不替换原consumer、服务或事务线程池。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class IsolatedDatabaseConfiguration {
        /** Spring和Flyway统一独占地址。 */
        @Bean DynamicPropertyRegistrar isolatedDatabaseProperties(){return registry->{registry.add("spring.datasource.url",()->DATABASE_URL);registry.add("spring.flyway.url",()->DATABASE_URL);registry.add("things-link.outbox.publisher.enabled",()->"false");registry.add("spring.kafka.listener.auto-startup",()->"false");registry.add("things-link.notification.retry.enabled",()->"false");};}
    }

    /** expectedWrites同时约束Outbox与真实poll schedule；REJECTED和版本相等是控制组。 */
    private enum Case {
        ACTIVE_STALE(3,2,DeviceConfigReply.Status.APPLIED,1), ACTIVE_EQUAL(3,3,DeviceConfigReply.Status.APPLIED,0),
        ACTIVE_REJECTED(3,2,DeviceConfigReply.Status.REJECTED,0), ARCHIVED_STALE(3,2,DeviceConfigReply.Status.APPLIED,0),
        DELETED_STALE(3,2,DeviceConfigReply.Status.APPLIED,0);
        private final int currentVersion,replyVersion,expectedWrites; private final DeviceConfigReply.Status status;
        Case(int currentVersion,int replyVersion,DeviceConfigReply.Status status,int expectedWrites){this.currentVersion=currentVersion;this.replyVersion=replyVersion;this.status=status;this.expectedWrites=expectedWrites;}
    }
    /** 所有身份均来自真实父表。 */
    private record Fixture(UUID tenantId,UUID projectId,UUID accountId,UUID gatewayType,UUID subType,UUID gatewayId,UUID subId){}
}
