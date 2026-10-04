package com.things.link.bootstrap.rule;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.rule.application.RuleNotificationRetryScheduler;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-109：用真实 PostgreSQL 独立验证规则通知函数的提交、幂等和租约竞争语义。
 *
 * <p>原 S9 Kafka 闭环上下文的后台协调器能够领取逐句提交的 QUEUED 行，SQL 用例不能假定独占。
 * 本类仍使用 bootstrap 的完整 Flyway 迁移，建立专用数据库容器；其他缓存上下文的连接
 * 无法看到该库，集群级角色也不会与默认数据库的迁移冲突。当前上下文的两个领取方由测试替身隔离，真实 Kafka 闭环继续留在 S9 原类。</p>
 *
 * <p>本类不使用外层回滚事务：每条状态变更仍自动提交，另一个应用角色物理连接读取持久事实。
 * 竞争用例仅在领取方显式保留事务，确定性验证 SKIP LOCKED 和提交后有效租约，均不依赖 sleep。</p>
 */
@Import(RuleNotificationStateMachineIntegrationTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"STATE_MACHINE_POSTGRES"})
class RuleNotificationStateMachineIntegrationTests extends AbstractIntegrationTest {

    /** 专库由本类独占；随机后缀避免同 JVM 的上下文重建与其他测试数据库重名。 */
    private static final String DATABASE_NAME = "rule_notification_" + UUID.randomUUID().toString().replace("-", "");

    /**
     * 数据库角色属于整个 PostgreSQL 集群；仅 CREATE DATABASE 不能隔离迁移中的 CREATE ROLE。
     * 使用与公共基类完全相同的镜像和测试 owner，独立容器只隔离运行状态，不修改生产迁移。
     */
    private static final PostgreSQLContainer<?> STATE_MACHINE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());

    /** 独立容器启动后提供实际随机端口，不能指向开发环境或默认集成测试数据库。 */
    private static final String DATABASE_URL = startIsolatedDatabase();

    /** 验证 Spring 的运行时数据源确实绑定专库，防止动态属性优先级退回共享库。 */
    @Autowired
    private JdbcTemplate applicationJdbc;

    /** 本类仅验证 SQL 函数；真实 worker 会抢先领取已提交行，须在这个专库上下文中隔离。 */
    @MockitoBean
    private NotificationWorkCoordinator notificationWorkCoordinator;

    /** 重试领取也由用例逐步驱动，不能让自动扫描提前改变待断言状态。 */
    @MockitoBean
    private RuleNotificationRetryScheduler ruleNotificationRetryScheduler;

    /** 专库配置只导入本类，保留父类凭据、Redis 和 bootstrap 全部标准迁移。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {

        /**
         * Spring 7 的 registrar 在测试静态动态属性注册后、普通单例实例化前执行。
         * 使用此顺序避免父类 DynamicPropertySource 把专库 URL 覆盖回默认库，不改公共测试基类。
         */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }

    /** 在任何夹具写入前验证专库和非 owner 应用角色，属性误覆盖必须立即失败。 */
    @BeforeEach
    void verifyDatabaseIsolation() {
        assertDatabaseAndRole(applicationJdbc, APP_ROLE);
        assertThat(applicationJdbc.queryForObject("""
                SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user
                """, Boolean.class)).isTrue();
    }

    /** 只清理本类专库的投递行，避免竞争用例的未完成 QUEUED 事实进入下一用例领取集合。 */
    @AfterEach
    void clearIsolatedDeliveryFacts() throws SQLException {
        try (Connection connection = ownerConnection()) {
            JdbcTemplate owner = jdbc(connection);
            assertDatabaseAndRole(owner, STATE_MACHINE_POSTGRES.getUsername());
            owner.update("DELETE FROM rule_notification_delivery");
        }
    }

    /** 保留 S9 原 SQL 用例全部状态/CAS/幂等断言，并由第二个连接逐阶段确认已经提交。 */
    @Test
    void notificationRetryStateMachineRequeuesThroughOutboxAttempt() throws SQLException {
        Fixture fixture = seedFixture();
        try (Connection writerConnection = applicationConnection(fixture);
             Connection observerConnection = applicationConnection(fixture)) {
            JdbcTemplate writer = jdbc(writerConnection);
            JdbcTemplate observer = jdbc(observerConnection);
            assertDifferentBackends(writer, observer);
            UUID deliveryId = Uuid7.generate();
            UUID messageId = Uuid7.generate();
            Instant firstEnqueued = Instant.now().minusSeconds(10);

            assertThat(acceptance(writer, fixture, deliveryId, messageId, 1, "body", firstEnqueued))
                    .isEqualTo("READY");
            assertCommittedState(observer, deliveryId, "QUEUED", 0);

            UUID firstDispatchToken = Uuid7.generate();
            assertDispatchClaim(writer, firstDispatchToken, deliveryId, 1);
            assertThat(delivery(observer, deliveryId).get("dispatch_lease_token")).isEqualTo(firstDispatchToken);
            Instant firstStarted = Instant.now();
            assertThat(writer.queryForObject("""
                    SELECT rule_notification_delivery_start_claimed(?, ?, 1, ?, ?, ?)
                    """, Boolean.class, fixture.projectId(), deliveryId, firstDispatchToken,
                    Timestamp.from(firstStarted), Timestamp.from(firstStarted.plusSeconds(30)))).isTrue();
            assertCommittedState(observer, deliveryId, "SENDING", 1);
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 1, "body", Instant.now()))
                    .isEqualTo("IDEMPOTENT_REPLAY");
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 1, "collision", Instant.now()))
                    .isEqualTo("REJECTED");
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 2, "body", Instant.now()))
                    .isEqualTo("REJECTED");
            assertThat(writer.queryForObject("""
                    SELECT rule_notification_delivery_retry(?, ?, 1, ?, 'SMTP_FAILURE', ?)
                    """, Boolean.class, fixture.projectId(), deliveryId,
                    Timestamp.from(Instant.now().minusSeconds(1)),
                    Timestamp.from(Instant.now().minusSeconds(2)))).isTrue();
            assertCommittedState(observer, deliveryId, "RETRY_SCHEDULED", 1);

            UUID leaseToken = Uuid7.generate();
            List<Map<String, Object>> claimed = writer.queryForList(
                    "SELECT * FROM claim_rule_notification_retries(?, 10, 30)", leaseToken);
            assertThat(claimed).singleElement().satisfies(row -> {
                assertThat(row.get("id")).isEqualTo(deliveryId);
                assertThat(row.get("next_attempt_no")).isEqualTo(2);
            });
            assertThat(delivery(observer, deliveryId).get("retry_lease_token")).isEqualTo(leaseToken);
            UUID retryOutboxId = Uuid7.generate();
            assertThat(writer.queryForObject("""
                    SELECT rule_notification_delivery_requeue(?, ?, ?, ?, 2, ?)
                    """, Boolean.class, fixture.projectId(), deliveryId, leaseToken, retryOutboxId,
                    Timestamp.from(Instant.now()))).isTrue();
            assertCommittedState(observer, deliveryId, "QUEUED", 1);
            assertThat(delivery(observer, deliveryId).get("last_outbox_event_id")).isEqualTo(retryOutboxId);
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 2, "body", Instant.now()))
                    .isEqualTo("READY");
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 1, "body", Instant.now()))
                    .isEqualTo("IDEMPOTENT_REPLAY");

            UUID secondDispatchToken = Uuid7.generate();
            assertDispatchClaim(writer, secondDispatchToken, deliveryId, 2);
            assertThat(delivery(observer, deliveryId).get("dispatch_lease_token")).isEqualTo(secondDispatchToken);
            assertThat(writer.queryForObject("""
                    SELECT rule_notification_delivery_start_claimed(?, ?, 2, ?, ?, ?)
                    """, Boolean.class, fixture.projectId(), deliveryId, secondDispatchToken,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(30)))).isTrue();
            assertCommittedState(observer, deliveryId, "SENDING", 2);
            assertThat(writer.queryForObject("""
                    SELECT rule_notification_delivery_succeed(?, ?, 2, 'provider-test', ?)
                    """, Boolean.class, fixture.projectId(), deliveryId, Timestamp.from(Instant.now()))).isTrue();

            Map<String, Object> terminal = delivery(observer, deliveryId);
            assertThat(terminal.get("status")).isEqualTo("DELIVERED");
            assertThat(terminal.get("attempt_count")).isEqualTo(2);
            assertThat(terminal.get("last_outbox_event_id")).isEqualTo(retryOutboxId);
            assertThat(terminal.get("retry_lease_token")).isNull();
            assertThat(acceptance(writer, fixture, deliveryId, messageId, 2, "body", Instant.now()))
                    .isEqualTo("IDEMPOTENT_REPLAY");
        }
    }

    /** 已提交 QUEUED 可被另一个合法 worker 领取；持锁和租约提交后，后来的领取者都只能得到空集合。 */
    @Test
    void committedQueuedDeliveryCanBeClaimedByCompetingApplicationConnection() throws SQLException {
        Fixture fixture = seedFixture();
        try (Connection contenderConnection = applicationConnection(fixture);
             Connection competitorConnection = applicationConnection(fixture)) {
            JdbcTemplate contender = jdbc(contenderConnection);
            JdbcTemplate competitor = jdbc(competitorConnection);
            assertDifferentBackends(contender, competitor);
            UUID deliveryId = Uuid7.generate();
            UUID messageId = Uuid7.generate();
            assertThat(acceptance(contender, fixture, deliveryId, messageId, 1, "body", Instant.now()))
                    .isEqualTo("READY");
            assertCommittedState(competitor, deliveryId, "QUEUED", 0);

            // 只有竞争领取显式保留事务：另一个连接面对真实行锁必须 SKIP LOCKED，不能等待调度时序。
            competitorConnection.setAutoCommit(false);
            UUID competingToken = Uuid7.generate();
            assertThat(competitor.queryForList(
                    "SELECT * FROM claim_rule_notification_dispatches(?, 10, 300)", competingToken))
                    .singleElement().satisfies(row -> {
                        assertThat(row.get("id")).isEqualTo(deliveryId);
                        assertThat(row.get("attempt_no")).isEqualTo(1);
                    });
            assertThat(delivery(competitor, deliveryId).get("dispatch_lease_token")).isEqualTo(competingToken);
            assertThat(contender.queryForList(
                    "SELECT * FROM claim_rule_notification_dispatches(?, 10, 300)", Uuid7.generate()))
                    .as("竞争连接已经持有该投递行锁，原测试线程没有独占领取权")
                    .isEmpty();

            competitorConnection.commit();
            Map<String, Object> committed = delivery(contender, deliveryId);
            assertThat(committed.get("status")).isEqualTo("QUEUED");
            assertThat(committed.get("dispatch_lease_token")).isEqualTo(competingToken);
            assertThat(contender.queryForObject("""
                    SELECT dispatch_leased_until > clock_timestamp()
                      FROM rule_notification_delivery WHERE id = ?
                    """, Boolean.class, deliveryId)).isTrue();
            assertThat(contender.queryForList(
                    "SELECT * FROM claim_rule_notification_dispatches(?, 10, 300)", Uuid7.generate()))
                    .as("竞争领取提交后仍由有效租约排除重复领取")
                    .isEmpty();
        }
    }

    /** 第三来源贯穿真实受理、领取与重试；旧来源不能冒领自动化投递。 */
    @Test
    void automationNotificationSurvivesClaimRetryAndRejectsConflictingSources() throws SQLException {
        AutoFixture auto = seedAutomation(); Fixture f = auto.base();
        try (Connection connection = applicationConnection(f)) {
            JdbcTemplate jdbc = jdbc(connection);
            var store = new com.things.link.rule.infrastructure.persistence.JdbcRuleNotificationDeliveryStore(jdbc);
            UUID id = Uuid7.generate();
            var request = automationRequest(auto, id, 1);
            assertThat(store.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.READY);
            var claim = store.claimDispatches(10, java.time.Duration.ofSeconds(30));
            assertThat(claim.deliveries()).singleElement().satisfies(row -> {
                assertThat(row.automationId()).isEqualTo(auto.id());
                assertThat(row.automationVersionId()).isEqualTo(auto.version());
                assertThat(row.automationExecutionId()).isEqualTo(auto.execution());
                assertThat(row.ruleId()).isNull(); assertThat(row.sceneId()).isNull();
            });
            Instant now = Instant.now();
            assertThat(store.startClaimed(f.projectId(), id, 1, claim.leaseToken(), now, now.plusSeconds(30))).isTrue();
            assertThat(store.accept(request)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);
            assertThat(acceptance(jdbc, f, id, Uuid7.generate(), 1, "body", now)).isEqualTo("REJECTED");
            assertThat(store.markRetry(f.projectId(), id, 1, now.minusSeconds(1), "SMTP_FAILURE", now)).isTrue();
            var retry = store.claimDueRetries(10, java.time.Duration.ofSeconds(30));
            assertThat(retry.deliveries()).hasSize(1);
            var candidate = retry.deliveries().getFirst();
            assertThat(candidate.automationExecutionId()).isEqualTo(auto.execution());
            assertThat(store.matchesClaimedRetry(candidate, retry.leaseToken(), now)).isTrue();
            var forged = new com.things.link.rule.application.RuleNotificationDeliveryStore.RetryCandidate(
                    candidate.id(), candidate.tenantId(), candidate.projectId(), null, null, null, null, null, null,
                    candidate.deviceId(), candidate.channel(), candidate.recipient(), candidate.subject(), candidate.body(),
                    candidate.traceId(), candidate.nextAttemptNo(), auto.id(), auto.version(), Uuid7.generate());
            assertThat(store.matchesClaimedRetry(forged, retry.leaseToken(), now)).isFalse();
            assertThat(store.requeueClaimedRetry(f.projectId(), id, retry.leaseToken(), Uuid7.generate(), 2, now)).isTrue();
            assertThat(store.accept(automationRequest(auto, id, 2))).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.READY);
        }
    }

    /** 来源必须完整互斥；缺失执行、另一个设备或另一版本不会创建投递事实。 */
    @Test
    void automationNotificationRejectsPartialMixedAndUnrelatedExecution() throws SQLException {
        AutoFixture auto = seedAutomation(); Fixture f = auto.base();
        try (Connection connection = applicationConnection(f)) {
            JdbcTemplate jdbc = jdbc(connection);
            var store = new com.things.link.rule.infrastructure.persistence.JdbcRuleNotificationDeliveryStore(jdbc);
            var valid = automationRequest(auto, Uuid7.generate(), 1);
            var mixed = new com.things.link.shared.message.RuleNotificationDeliveryRequest(valid.eventId(), f.tenantId(), f.projectId(),
                    f.ruleId(), null, null, null, null, null, f.deviceId(), "EMAIL", "ops@example.com", "subject", "body", "trace", 1,
                    Instant.now(), auto.id(), auto.version(), auto.execution());
            assertThat(mixed.validSource()).isFalse();
            assertThat(store.accept(mixed)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.REJECTED);
            var missing = com.things.link.shared.message.RuleNotificationDeliveryRequest.automation(valid.eventId(), f.tenantId(), f.projectId(),
                    auto.id(), auto.version(), Uuid7.generate(), f.deviceId(), "EMAIL", "ops@example.com", "subject", "body", "trace", 1, Instant.now());
            assertThat(store.accept(missing)).isEqualTo(com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.REJECTED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_notification_delivery WHERE project_id=?", Integer.class, f.projectId())).isZero();
        }
    }

    /** 真实复合FK阻止悬空执行；原事务回滚同时撤销设备投递。 */
    @Test
    void automationDeviceDeliveryHasRealExecutionForeignKeyAndRollsBack() throws SQLException {
        AutoFixture auto = seedAutomation(); Fixture f = auto.base();
        try (Connection connection = applicationConnection(f)) {
            JdbcTemplate jdbc = jdbc(connection);
            var store = new com.things.link.rule.infrastructure.persistence.JdbcRuleDeviceActionDeliveryStore(jdbc);
            UUID id = Uuid7.generate(); Instant now = Instant.now();
            store.recordAutomation(id,f.tenantId(),f.projectId(),auto.id(),auto.version(),auto.execution(),f.deviceId(),
                    com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,null,"REJECTED","DEVICE_OFFLINE","trace",now);
            store.recordAutomation(id,f.tenantId(),f.projectId(),auto.id(),auto.version(),auto.execution(),f.deviceId(),
                    com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,null,"REJECTED","DEVICE_OFFLINE","trace",now);
            assertThat(jdbc.queryForObject("SELECT automation_execution_id FROM rule_device_action_delivery WHERE id=?", UUID.class,id)).isEqualTo(auto.execution());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.recordAutomation(id,f.tenantId(),f.projectId(),auto.id(),auto.version(),auto.execution(),f.deviceId(),
                    com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,null,"REJECTED","CONFLICT","trace",now)).isInstanceOf(IllegalStateException.class);
            connection.setAutoCommit(false);
            UUID rolledBack = Uuid7.generate();
            store.recordAutomation(rolledBack,f.tenantId(),f.projectId(),auto.id(),auto.version(),auto.execution(),f.deviceId(),
                    com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,null,"REJECTED","DEVICE_OFFLINE","trace",now);
            connection.rollback(); connection.setAutoCommit(true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_device_action_delivery WHERE id=?",Integer.class,rolledBack)).isZero();
            try(Connection ownerConnection=ownerConnection()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc(ownerConnection).update(
                    "DELETE FROM rule_automation_execution WHERE id=?",auto.execution())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                jdbc(ownerConnection).update("DELETE FROM rule_device_action_delivery WHERE project_id=?",f.projectId());
            }
        }
    }

    /** 原场景JDBC入口确实调用13参数SQL函数，不能由mock隐藏参数数量错误。 */
    @Test
    void legacySceneDeviceDeliveryStillUsesOriginalSource() throws SQLException {
        Fixture f=seedFixture();UUID scene=Uuid7.generate(), version=Uuid7.generate(), execution=Uuid7.generate(), action=Uuid7.generate();
        try(Connection connection=ownerConnection()) {
            var owner=jdbc(connection);
            owner.update("INSERT INTO rule_scene(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?,?,?,'legacy scene',?,now(),now())",scene,f.tenantId(),f.projectId(),f.accountId());
            owner.update("INSERT INTO rule_scene_version(id,tenant_id,project_id,scene_id,version_number,conditions,actions,created_by,created_at) VALUES (?,?,?,?,1,'[]','[]',?,now())",version,f.tenantId(),f.projectId(),scene,f.accountId());
        }
        try(Connection connection=applicationConnection(f)) {
            var store=new com.things.link.rule.infrastructure.persistence.JdbcRuleDeviceActionDeliveryStore(jdbc(connection));
            store.recordScene(action,f.tenantId(),f.projectId(),scene,version,execution,f.deviceId(),
                    com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND,null,"REJECTED","DEVICE_OFFLINE","trace",Instant.now());
            assertThat(jdbc(connection).queryForObject("SELECT scene_execution_id FROM rule_device_action_delivery WHERE id=?",UUID.class,action)).isEqualTo(execution);
        } finally {try(Connection connection=ownerConnection()) {jdbc(connection).update("DELETE FROM rule_device_action_delivery WHERE project_id=?",f.projectId());}}
    }

    private com.things.link.shared.message.RuleNotificationDeliveryRequest automationRequest(AutoFixture auto,UUID delivery,int attempt) {
        Fixture f=auto.base();
        return com.things.link.shared.message.RuleNotificationDeliveryRequest.automation(delivery,f.tenantId(),f.projectId(),
                auto.id(),auto.version(),auto.execution(),f.deviceId(),"EMAIL","ops@example.com","subject","body","trace",attempt,Instant.now());
    }

    /** 只建立真实FK骨架；运行领取/求值不是此投递兼容用例的证据范围。 */
    private AutoFixture seedAutomation() throws SQLException {
        Fixture f=seedFixture();UUID auto=Uuid7.generate(),version=Uuid7.generate(),execution=Uuid7.generate(),event=Uuid7.generate();
        try(Connection connection=ownerConnection()) {
            var owner=jdbc(connection);
            owner.update("INSERT INTO rule_automation(id,tenant_id,project_id,name,status,version,created_by,created_at,updated_at) VALUES (?,?,?,'auto','DRAFT',1,?,now(),now())",auto,f.tenantId(),f.projectId(),f.accountId());
            owner.update("INSERT INTO rule_automation_version(id,tenant_id,project_id,automation_id,version_number,trigger_type,trigger_config,conditions,actions,created_by,created_at) VALUES (?,?,?,?,1,'PROPERTY_REPORTED','{}','[]','[{}]',?,now())",version,f.tenantId(),f.projectId(),auto,f.accountId());
            owner.update("INSERT INTO rule_automation_event_receipt(id,tenant_id,project_id,source_event_id,device_id,accepted_at,admitted_at,source_digest,plan,result,expires_at) VALUES (?,?,?,?,?,now(),now(),repeat('a',64),'[]','ACCEPTED',now()+interval '721 hours')",Uuid7.generate(),f.tenantId(),f.projectId(),event,f.deviceId());
            owner.update("INSERT INTO rule_automation_execution(id,tenant_id,project_id,automation_id,automation_version_id,trigger_type,occurrence_key,source_event_id,device_id,input_snapshot,input_digest,responsible_account_id,trace_id,occurred_at,accepted_at,status,created_at,recovery_deadline) VALUES (?,?,?,?,?,'PROPERTY_REPORTED',?,?,?,'{}',repeat('a',64),?,'trace',now(),now(),'QUEUED',now(),now()+interval '24 hours')",execution,f.tenantId(),f.projectId(),auto,version,"event:"+event,event,f.deviceId(),f.accountId());
        }
        return new AutoFixture(f,auto,version,execution);
    }
    private record AutoFixture(Fixture base,UUID id,UUID version,UUID execution) {}

    /** 仅启动本类实例，Spring 关闭连接池后由 本类OwnedTestContainers 清理容器和全部专库夹具。 */
    private static String startIsolatedDatabase() {
        STATE_MACHINE_POSTGRES.start();
        return STATE_MACHINE_POSTGRES.getJdbcUrl();
    }

    /** 最小合法骨架只供通知 SQL 同属校验，不发布规则、不启动脚本和数据面物模型链路。 */
    private Fixture seedFixture() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection connection = ownerConnection()) {
            JdbcTemplate owner = jdbc(connection);
            assertDatabaseAndRole(owner, STATE_MACHINE_POSTGRES.getUsername());
            owner.update("INSERT INTO sys_tenant (id, name) VALUES (?, 'D-109 通知测试租户')", fixture.tenantId());
            owner.update("""
                    INSERT INTO sys_account (id, email, password_hash, display_name)
                    VALUES (?, ?, '{noop}unused', 'D-109 测试账号')
                    """, fixture.accountId(), "d109-" + fixture.accountId() + "@example.com");
            owner.update("""
                    INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                    VALUES (?, ?, 'D-109 通知测试项目', 'sh-1', ?)
                    """, fixture.projectId(), fixture.tenantId(),
                    "d109" + fixture.projectId().toString().replace("-", "").substring(0, 16));
            owner.update("""
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name,
                                          access_protocol, device_kind, status)
                    VALUES (?, ?, ?, 'notification-type', '通知测试类型', 'STANDARD', 'DIRECT', 'DRAFT')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            owner.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                    VALUES (?, ?, ?, ?, 'notification-device', '通知测试设备')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.update("""
                    INSERT INTO rule_message (id, tenant_id, project_id, name, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, 'D-109 通知测试规则', ?, now(), now())
                    """, fixture.ruleId(), fixture.tenantId(), fixture.projectId(), fixture.accountId());
            owner.update("""
                    INSERT INTO rule_version (id, tenant_id, project_id, rule_id, version_number,
                                              source, source_sha256, created_by, created_at)
                    VALUES (?, ?, ?, ?, 1, 'input => input',
                            encode(digest('input => input', 'sha256'), 'hex'), ?, now())
                    """, fixture.versionId(), fixture.tenantId(), fixture.projectId(), fixture.ruleId(),
                    fixture.accountId());
            owner.update("UPDATE rule_message SET status = 'ACTIVE', active_version_id = ? WHERE id = ?",
                    fixture.versionId(), fixture.ruleId());
        }
        return fixture;
    }

    /** owner 仅负责专库夹具与清理，所有待测生产函数必须使用 applicationConnection。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, STATE_MACHINE_POSTGRES.getUsername(),
                STATE_MACHINE_POSTGRES.getPassword());
    }

    /** 每个调用者得到独立 APP_ROLE 连接；会话范围只用于 RLS 事实读取，不改变函数自己的受控权限。 */
    private static Connection applicationConnection(Fixture fixture) throws SQLException {
        Connection connection = DriverManager.getConnection(DATABASE_URL, APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(connection.getAutoCommit()).as("状态机步骤必须逐句提交").isTrue();
            JdbcTemplate jdbcTemplate = jdbc(connection);
            assertDatabaseAndRole(jdbcTemplate, APP_ROLE);
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, false)", String.class,
                    fixture.projectId().toString());
            return connection;
        } catch (RuntimeException | Error exception) {
            connection.close();
            throw exception;
        }
    }

    /** JdbcTemplate 不关闭交给它的物理连接，生命周期由各测试的 try-with-resources 明确管理。 */
    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    /** 同时断言数据库与运行角色，避免测试在错误库或超级用户环境中假阳性。 */
    private static void assertDatabaseAndRole(JdbcTemplate jdbcTemplate, String expectedRole) {
        Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT current_database(), current_user");
        assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
        assertThat(identity.get("current_user")).isEqualTo(expectedRole);
    }

    /** PostgreSQL 后端 PID 不同才能证明跨连接可见性和行锁竞争，两个 JDBC 包装器并不够。 */
    private static void assertDifferentBackends(JdbcTemplate first, JdbcTemplate second) {
        assertThat(first.queryForObject("SELECT pg_backend_pid()", Integer.class))
                .isNotEqualTo(second.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

    /** 用生产三态入口保留不可变事实碰撞、重放和禁止越级尝试的原断言。 */
    private static String acceptance(JdbcTemplate jdbcTemplate, Fixture fixture, UUID deliveryId,
                                     UUID messageId, int attemptNo, String body, Instant enqueuedAt) {
        return jdbcTemplate.queryForObject("""
                SELECT rule_notification_delivery_acceptance(
                    ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, 'EMAIL',
                    'ops@example.com', 'subject', ?, 'trace-s9-3-retry', ?, ?)
                """, String.class, deliveryId, fixture.tenantId(), fixture.projectId(), fixture.ruleId(),
                fixture.versionId(), messageId, fixture.deviceId(), body, attemptNo, Timestamp.from(enqueuedAt));
    }

    /** 独占专库中的正常状态推进仍须精确领取目标行和指定尝试，不能把空领取改为忽略。 */
    private static void assertDispatchClaim(JdbcTemplate jdbcTemplate, UUID token, UUID deliveryId, int attemptNo) {
        assertThat(jdbcTemplate.queryForList("SELECT * FROM claim_rule_notification_dispatches(?, 10, 30)", token))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("id")).isEqualTo(deliveryId);
                    assertThat(row.get("attempt_no")).isEqualTo(attemptNo);
                });
    }

    /** 观察连接只能看见已提交数据；阶段断言因此不能被同事务内的临时状态蒙混通过。 */
    private static void assertCommittedState(JdbcTemplate observer, UUID deliveryId, String status, int attemptCount) {
        Map<String, Object> row = delivery(observer, deliveryId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("attempt_count")).isEqualTo(attemptCount);
    }

    /** 通过应用角色的项目 RLS 读取租约与终态，owner 不参与被测事实断言。 */
    private static Map<String, Object> delivery(JdbcTemplate jdbcTemplate, UUID deliveryId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, attempt_count, last_outbox_event_id, retry_lease_token, dispatch_lease_token
                  FROM rule_notification_delivery WHERE id = ?
                """, deliveryId);
    }

    /** 记录满足生产迁移同属外键的最小身份集合，每个用例使用独立 UUID。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId,
                           UUID ruleId, UUID versionId) {
    }
}
