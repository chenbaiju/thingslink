package com.things.link.bootstrap.alarm;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * ADR0069两域迁移的真实旧库升级、受限停止CAS及第三次崩溃领取验收。
 * 不启动Spring、不手改旧迁移/约束，不用APP通用UPDATE代替新受限函数；所有旧事实完整保留。
 */
@Testcontainers
class NotificationFreezeMigrationTests {
    /** Flyway创建运行角色，因此实例隔离而不是与其他测试共享schema。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("notification_freeze_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 全量迁移目录与Bootstrap一致，不能裁掉跨域FK或现有守卫。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser"};
    /** 此前已提交的最后迁移版本。 */
    private static final String LEGACY_VERSION = "20260904.0100";
    /** 两个新迁移按全局版本顺序同时升到规则域0210。 */
    private static final String FINAL_VERSION = "20260904.0210";
    /** 专库APP凭据仅用于真实权限验收。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 与容器Flyway角色占位符一致的测试密码。 */
    private static final String APP_PASSWORD = "thingslink";
    /** PostgreSQL微秒精度，不让JSON时间舍入影响完整行比较。 */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    /** 独立JSON映射不需要业务Spring上下文。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 单个顺序流程证明旧缺陷、升级无改写、两域CAS/事务/ACL及PUSH兼容，不依赖测试排序。 */
    @Test
    void upgradesWithoutRewritingAndRestrictsFrozenRetryMaintenance() throws Exception {
        flyway(LEGACY_VERSION).migrate();
        Fixture fixture = seedParents();
        UUID alarmThird = seedDelivery(Domain.ALARM, fixture, "SENDING", 3, "EMAIL", NOW.minusSeconds(60));
        UUID ruleThird = seedDelivery(Domain.RULE, fixture, "SENDING", 3, "EMAIL", NOW.minusSeconds(60));
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, Domain.ALARM, UUID.randomUUID())).as("旧告警SQL漏领第三次崩溃SENDING").isEmpty();
            app.commit();
        }
        Map<String, List<String>> oldFacts = facts(fixture.projectId());
        assertThat(flyway(FINAL_VERSION).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(facts(fixture.projectId())).as("升级只添加受限函数和注释，不改历史投递事实").isEqualTo(oldFacts);
        assertCatalog();
        for (Domain domain : Domain.values()) {
            UUID third = domain == Domain.ALARM ? alarmThird : ruleThird;
            assertThirdAttemptAndNarrowCas(domain, fixture, third);
            assertRetryRelationAndRollback(domain, fixture);
            assertNonCandidatesAndTerminalPreservation(domain, fixture);
            assertLeaseExpiryWhileProjectLockWaits(domain, fixture);
        }
        assertRuleRequeueKeepsCurrentAttemptRelation(fixture);
        assertPushExcludedFromFrozenStop(fixture);
        Map<String, List<String>> completed = facts(fixture.projectId());
        assertThat(flyway(FINAL_VERSION).migrate().migrationsExecuted).isZero();
        assertThat(facts(fixture.projectId())).isEqualTo(completed);
        try (Connection owner = ownerConnection()) {
            assertThat(integer(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id=?", fixture.projectId())).isZero();
        }
    }

    /** 第三次过期SENDING合法领取仍返回3；错误身份/令牌/时间/状态及旧lease都不能推进。 */
    private void assertThirdAttemptAndNarrowCas(Domain domain, Fixture fixture, UUID id) throws SQLException {
        UUID token = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, token)).containsExactly(new Claim(id, 3));
            app.commit();
        }
        JsonNode before = row(domain, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, UUID.randomUUID(), fixture.projectId(), id, token, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), UUID.randomUUID(), id, token, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), UUID.randomUUID(), token, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, UUID.randomUUID(), 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 4, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 2, NOW)).isFalse();
            assertThat(stop(app, domain, null, fixture.projectId(), id, token, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, null, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 0, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 3, null)).isFalse();
            app.commit();
        }
        assertThat(row(domain, id)).isEqualTo(before);
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE " + domain.table() + " SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=?", id);
        }
        JsonNode activeSending = row(domain, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 3, NOW)).isFalse();
            app.commit();
        }
        assertThat(row(domain, id)).isEqualTo(activeSending);
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE " + domain.table() + " SET next_attempt_at=clock_timestamp()-interval '1 second',retry_leased_until=clock_timestamp()-interval '1 second' WHERE id=?", id);
        }
        JsonNode expired = row(domain, id);
        UUID replacement = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            // 即使p_now回填到lease有效期内，也不能绕过数据库真实时钟的过期判断。
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 3, NOW.minusSeconds(3600))).isFalse();
            app.commit();
            assertThat(row(domain, id)).isEqualTo(expired);
            assertThat(claim(app, domain, replacement)).containsExactly(new Claim(id, 3));
            app.commit();
        }
        JsonNode reclaimed = row(domain, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 3, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, replacement, 3, NOW)).isTrue();
            app.commit();
        }
        assertTerminalPreservedFields(domain, id, reclaimed, 3);
        JsonNode terminal = row(domain, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, replacement, 3, NOW.plusSeconds(1))).isFalse();
            app.commit();
        }
        assertThat(row(domain, id)).isEqualTo(terminal);
    }

    /** RETRY的claim号为count+1，但冻结不消耗下一个attempt；真实事务回滚保留整个claim快照。 */
    private void assertRetryRelationAndRollback(Domain domain, Fixture fixture) throws SQLException {
        UUID id = seedDelivery(domain, fixture, "RETRY_SCHEDULED", 1, "WEBHOOK", NOW.minusSeconds(60));
        UUID token = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, token)).containsExactly(new Claim(id, 2));
            app.commit();
        }
        JsonNode before = row(domain, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 1, NOW)).isFalse();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 2, NOW)).isTrue();
            assertThat(text(app, "SELECT status FROM " + domain.table() + " WHERE id=?", id)).isEqualTo("DEAD_LETTER");
            app.rollback();
        }
        assertThat(row(domain, id)).isEqualTo(before);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 2, NOW)).isTrue();
            app.commit();
        }
        assertTerminalPreservedFields(domain, id, before, 1);
    }

    /** 没有余次的RETRY、尚有效SENDING及成功终态不应被领取；即使带旧token也不能覆盖终态/QUEUED。 */
    private void assertNonCandidatesAndTerminalPreservation(Domain domain, Fixture fixture) throws SQLException {
        UUID exhausted = seedDelivery(domain, fixture, "RETRY_SCHEDULED", 3, "EMAIL", NOW.minusSeconds(60));
        UUID active = seedDelivery(domain, fixture, "SENDING", 2, "EMAIL", Instant.now().plusSeconds(3600));
        UUID succeeded = seedDelivery(domain, fixture, domain == Domain.ALARM ? "SUCCEEDED" : "DELIVERED", 2, "EMAIL", null);
        UUID queued = seedDelivery(domain, fixture, "QUEUED", 1, "EMAIL", NOW.minusSeconds(60));
        UUID token = UUID.randomUUID();
        try (Connection owner = ownerConnection()) {
            for (UUID id : List.of(exhausted, active, succeeded, queued))
                execute(owner, "UPDATE " + domain.table() + " SET retry_lease_token=?,retry_leased_until=clock_timestamp()+interval '1 minute' WHERE id=?", token, id);
        }
        Map<String, List<String>> before = facts(fixture.projectId());
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, UUID.randomUUID())).isEmpty();
            for (UUID id : List.of(exhausted, active, succeeded, queued))
                assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 2, NOW)).isFalse();
            app.commit();
        }
        assertThat(facts(fixture.projectId())).isEqualTo(before);
        // 清本例无效状态的领取字段，随后再次证明不是有效lease掩盖了错误候选条件。
        try (Connection owner = ownerConnection()) {
            for (UUID id : List.of(exhausted, active, succeeded, queued))
                execute(owner, "UPDATE " + domain.table() + " SET retry_lease_token=NULL,retry_leased_until=NULL WHERE id=?", id);
        }
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, UUID.randomUUID())).isEmpty();
            app.commit();
        }
    }

    /** 只读资格之后等待project锁，lease在等待中自然过期且token未改变，最终stop/requeue仍必须拒绝。 */
    private void assertLeaseExpiryWhileProjectLockWaits(Domain domain, Fixture fixture) throws Exception {
        UUID id = seedDelivery(domain, fixture, "SENDING", 1, "EMAIL", NOW.minusSeconds(60));
        UUID token = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, token)).containsExactly(new Claim(id, 1));
            app.commit();
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> checked = new CompletableFuture<>();
        try (Connection owner = ownerConnection(); Connection leaseOwner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE sys_project SET status=status WHERE id=?", fixture.projectId());
            int ownerPid = integer(owner, "SELECT pg_backend_pid()");
            Future<?> waited = worker.submit(() -> {
                try (Connection app = appConnection(fixture.projectId())) {
                    assertThat(text(app, "SELECT (retry_leased_until>clock_timestamp())::text FROM " + domain.table() + " WHERE id=?", id)).isEqualTo("true");
                    checked.complete(integer(app, "SELECT pg_backend_pid()"));
                    text(app, "SELECT id::text FROM sys_project WHERE id=? FOR SHARE", fixture.projectId());
                    assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, token, 1, NOW)).isFalse();
                    if (domain == Domain.RULE)
                        assertThat(text(app, "SELECT rule_notification_delivery_requeue(?,?,?,?,?,?)::text", fixture.projectId(), id,
                                token, UUID.randomUUID(), 1, Timestamp.from(NOW))).isEqualTo("false");
                    app.commit();
                    return null;
                }
            });
            int appPid = checked.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                    assertThat(text(owner, "SELECT (?=ANY(pg_blocking_pids(?)))::text", ownerPid, appPid)).isEqualTo("true"));
            // 原领取有效且真实SHARE等待已证实后，才缩短同token租约；建连耗时不消耗到期窗口。
            // 之后不再修改lease/token，让数据库时间自然越过截止点，最终CAS必须自行重新仲裁。
            execute(leaseOwner, "UPDATE " + domain.table() + " SET retry_leased_until=clock_timestamp()+interval '1 second' WHERE id=?", id);
            JsonNode before = mapper.readTree(text(leaseOwner, "SELECT row_to_json(d)::text FROM " + domain.table() + " d WHERE id=?", id));
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                    assertThat(text(owner, "SELECT (retry_leased_until<=clock_timestamp())::text FROM " + domain.table() + " WHERE id=?", id)).isEqualTo("true"));
            owner.commit();
            waited.get(10, TimeUnit.SECONDS);
            assertThat(row(domain, id)).isEqualTo(before);
        } finally {
            worker.shutdown();
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) { worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
        // 新领取者仍能收束同一事实，失败没有污染原尝试或制造不可恢复中间态。
        UUID renewed = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, domain, renewed)).containsExactly(new Claim(id, 1));
            app.commit();
            assertThat(stop(app, domain, fixture.tenantId(), fixture.projectId(), id, renewed, 1, NOW)).isTrue();
            app.commit();
        }
    }

    /** 新requeue不是一律拒绝：第三次SENDING恢复仍为第三次，RETRY只重排下一次，未来号必须拒绝。 */
    private void assertRuleRequeueKeepsCurrentAttemptRelation(Fixture fixture) throws SQLException {
        for (String state : List.of("SENDING", "RETRY_SCHEDULED")) {
            int current = state.equals("SENDING") ? 3 : 1;
            int expected = state.equals("SENDING") ? 3 : 2;
            UUID id = seedDelivery(Domain.RULE, fixture, state, current, "EMAIL", NOW.minusSeconds(60));
            UUID token = UUID.randomUUID();
            try (Connection app = appConnection(fixture.projectId())) {
                assertThat(claim(app, Domain.RULE, token)).containsExactly(new Claim(id, expected));
                app.commit();
            }
            JsonNode before = row(Domain.RULE, id);
            UUID eventId = UUID.randomUUID();
            try (Connection app = appConnection(fixture.projectId())) {
                assertThat(text(app, "SELECT rule_notification_delivery_requeue(?,?,?,?,?,?)::text", fixture.projectId(), id,
                        token, eventId, expected + 1, Timestamp.from(NOW))).isEqualTo("false");
                app.commit();
                assertThat(row(Domain.RULE, id)).isEqualTo(before);
                assertThat(text(app, "SELECT rule_notification_delivery_requeue(?,?,?,?,?,?)::text", fixture.projectId(), id,
                        token, eventId, expected, Timestamp.from(NOW))).isEqualTo("true");
                app.commit();
            }
            JsonNode queued = row(Domain.RULE, id);
            assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
            assertThat(queued.path("attempt_count").asInt()).isEqualTo(expected - 1);
            assertThat(queued.path("last_outbox_event_id").asText()).isEqualTo(eventId.toString());
            assertThat(queued.path("retry_lease_token").isNull()).isTrue();
            assertThat(queued.path("retry_leased_until").isNull()).isTrue();
        }
    }

    /** PUSH仍由原发送授权合同处理；第三次崩溃可领取，但新PROJECT_FROZEN函数明确不接管。 */
    private void assertPushExcludedFromFrozenStop(Fixture fixture) throws SQLException {
        UUID id = seedDelivery(Domain.ALARM, fixture, "SENDING", 3, "PUSH", NOW.minusSeconds(60));
        UUID token = UUID.randomUUID();
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(claim(app, Domain.ALARM, token)).containsExactly(new Claim(id, 3));
            app.commit();
        }
        JsonNode before = row(Domain.ALARM, id);
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(stop(app, Domain.ALARM, fixture.tenantId(), fixture.projectId(), id, token, 3, NOW)).isFalse();
            app.commit();
        }
        assertThat(row(Domain.ALARM, id)).isEqualTo(before);
    }

    /** 完整字段对照只扣除合同明确的停止维护字段；provider、旧Outbox与内容均不可改写。 */
    private void assertTerminalPreservedFields(Domain domain, UUID id, JsonNode before, int attempt) throws SQLException {
        JsonNode after = row(domain, id);
        assertThat(after.path("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(after.path("last_error_code").asText()).isEqualTo("PROJECT_FROZEN");
        assertThat(after.path("attempt_count").asInt()).isEqualTo(attempt);
        assertThat(after.path("provider_message_id").asText()).isEqualTo("legacy-provider");
        for (String field : List.of("next_attempt_at", "dispatch_lease_token", "dispatch_leased_until", "retry_lease_token", "retry_leased_until"))
            assertThat(after.path(field).isNull()).as(field).isTrue();
        assertThat(Instant.parse(after.path("terminal_at").asText())).isEqualTo(NOW);
        ObjectNode expected = (ObjectNode) before.deepCopy();
        ObjectNode actual = (ObjectNode) after.deepCopy();
        for (String field : List.of("status", "last_error_code", "next_attempt_at", "terminal_at", "updated_at", "dispatch_available_at",
                "dispatch_lease_token", "dispatch_leased_until", "retry_lease_token", "retry_leased_until")) { expected.remove(field); actual.remove(field); }
        assertThat(actual).isEqualTo(expected);
    }

    /** 受限函数保留固定search_path、PUBLIC无EXECUTE、APP可执行；rule直接UPDATE仍被拒绝。 */
    private void assertCatalog() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Domain domain : Domain.values()) {
                String signature = domain.function() + "(uuid,uuid,uuid,uuid,integer,timestamp with time zone)";
                assertThat(text(owner, "SELECT prosecdef::text FROM pg_proc WHERE oid=?::regprocedure", signature)).isEqualTo("true");
                assertThat(text(owner, "SELECT proconfig::text FROM pg_proc WHERE oid=?::regprocedure", signature)).contains("search_path=pg_catalog, public");
                assertThat(text(owner, "SELECT has_function_privilege(?,?,'EXECUTE')::text", APP_ROLE, signature)).isEqualTo("true");
                assertThat(integer(owner, "SELECT count(*) FROM pg_proc p,LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid=?::regprocedure AND a.grantee=0 AND a.privilege_type='EXECUTE'", signature)).isZero();
                assertThat(text(owner, "SELECT col_description(?::regclass,attnum) FROM pg_attribute WHERE attrelid=?::regclass AND attname='attempt_count'", domain.table(), domain.table()))
                        .contains("授权", "不等同外部供应商");
            }
            assertThat(text(owner, "SELECT has_table_privilege(?,'rule_notification_delivery','UPDATE')::text", APP_ROLE)).isEqualTo("false");
        }
        try (Connection app = appConnection(UUID.randomUUID())) {
            assertThat(text(app, "SELECT current_user")).isEqualTo(APP_ROLE);
            assertThat(text(app, "SELECT (NOT rolsuper AND NOT rolbypassrls)::text FROM pg_roles WHERE rolname=current_user")).isEqualTo("true");
            assertThat(integer(app, "SELECT count(*) FROM rule_notification_delivery")).isZero();
            assertThat(integer(app, "SELECT count(*) FROM alarm_notification_delivery")).isZero();
            Throwable denied = catchThrowable(() -> execute(app, "UPDATE rule_notification_delivery SET status=status WHERE id=?", UUID.randomUUID()));
            assertThat(denied).isInstanceOf(SQLException.class);
            assertThat(((SQLException) denied).getSQLState()).isEqualTo("42501");
            app.rollback();
        }
    }

    /** 完整父表外键均真实存在；SQL种子只用于迁移/领取，不冒称供应商实际发送。 */
    private Fixture seedParents() throws SQLException {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (Connection c = ownerConnection()) {
            c.setAutoCommit(false);
            execute(c, "INSERT INTO sys_tenant(id,name) VALUES (?, '通知冻结升级租户')", f.tenantId());
            execute(c, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '通知升级账号')", f.accountId(), f.accountId()+"@example.com");
            execute(c, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '通知升级项目', 'sh-1', 'notification_freeze_upgrade')", f.projectId(), f.tenantId());
            execute(c, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'nf_type', '通知类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", f.typeId(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'nf_device', '通知设备', 'ONLINE')", f.deviceId(), f.tenantId(), f.projectId(), f.typeId());
            execute(c, "INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?, ?, ?, '通知规则', 'TEMP_HIGH', ?, 'temperature', 'GT', 30, 'LT', 20, 'WARNING')", f.alarmRuleId(), f.tenantId(), f.projectId(), f.deviceId());
            execute(c, "INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,first_condition_at,last_received_at,last_value) VALUES (?, ?, ?, ?, 'DEVICE', ?, 'TEMP_HIGH', 'WARNING', 'ACTIVE', ?, ?, 31)", f.instanceId(), f.tenantId(), f.projectId(), f.alarmRuleId(), f.deviceId(), Timestamp.from(NOW), Timestamp.from(NOW));
            execute(c, "INSERT INTO alarm_notification_group(id,tenant_id,project_id,name) VALUES (?, ?, ?, '通知组')", f.groupId(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO alarm_notification_recipient(id,tenant_id,project_id,group_id,channel,target) VALUES (?, ?, ?, ?, 'EMAIL', 'ops@example.com')", f.recipientId(), f.tenantId(), f.projectId(), f.groupId());
            execute(c, "INSERT INTO alarm_notification_template(id,tenant_id,project_id,name,channel,subject_template,body_template) VALUES (?, ?, ?, '通知模板', 'EMAIL', '主题', '正文')", f.templateId(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO alarm_notification_binding(id,tenant_id,project_id,rule_id,group_id,template_id,channel) VALUES (?, ?, ?, ?, ?, ?, 'EMAIL')", f.bindingId(), f.tenantId(), f.projectId(), f.alarmRuleId(), f.groupId(), f.templateId());
            execute(c, "INSERT INTO rule_message(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?, ?, ?, '规则通知', ?, ?, ?)", f.ruleId(), f.tenantId(), f.projectId(), f.accountId(), Timestamp.from(NOW), Timestamp.from(NOW));
            execute(c, "INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,created_by,created_at) VALUES (?, ?, ?, ?, 1, 'input => input', ?, ?, ?)", f.ruleVersionId(), f.tenantId(), f.projectId(), f.ruleId(), "1b5985c52722858aa4360ce04fa587515e3a5de1a5b62e884e404583eafe7737", f.accountId(), Timestamp.from(NOW));
            c.commit();
        }
        return f;
    }

    /** 不同告警delivery使用独立真实事件，满足旧/新唯一键；规则delivery引用真实不可变版本。 */
    private UUID seedDelivery(Domain domain, Fixture f, String state, int attempt, String channel, Instant dueAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ownerConnection()) {
            c.setAutoCommit(false);
            if (domain == Domain.ALARM) {
                // WEBHOOK/PUSH使用同渠道的真实绑定与模板，不能用EMAIL父关系伪装兼容夹具。
                UUID binding = f.bindingId();
                UUID recipient = channel.equals("PUSH") ? null : f.recipientId();
                if (!channel.equals("EMAIL")) {
                    UUID template = UUID.randomUUID();
                    binding = UUID.randomUUID();
                    execute(c, "INSERT INTO alarm_notification_template(id,tenant_id,project_id,name,channel,subject_template,body_template) VALUES (?, ?, ?, ?, ?, '旧主题', '旧正文')",
                            template, f.tenantId(), f.projectId(), "通知模板" + channel, channel);
                    execute(c, "INSERT INTO alarm_notification_binding(id,tenant_id,project_id,rule_id,group_id,template_id,channel) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            binding, f.tenantId(), f.projectId(), f.alarmRuleId(), f.groupId(), template, channel);
                    if (channel.equals("WEBHOOK")) {
                        recipient = UUID.randomUUID();
                        execute(c, "INSERT INTO alarm_notification_recipient(id,tenant_id,project_id,group_id,channel,target) VALUES (?, ?, ?, ?, 'WEBHOOK', 'https://notify.example.com')",
                                recipient, f.tenantId(), f.projectId(), f.groupId());
                    }
                }
                UUID event = UUID.randomUUID();
                execute(c, "INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,received_at,condition_state,ack_state) VALUES (?, ?, ?, ?, 'ACTIVATED', ?, 'notification-upgrade', ?, 'ACTIVE', 'UNACKNOWLEDGED')", event, f.tenantId(), f.projectId(), f.instanceId(), UUID.randomUUID(), Timestamp.from(NOW));
                execute(c, "INSERT INTO alarm_notification_delivery(id,tenant_id,project_id,instance_id,alarm_event_id,binding_id,recipient_id,channel,target_snapshot,subject_snapshot,body_snapshot,template_version,status,attempt_count,max_attempts,next_attempt_at,provider_message_id,last_outbox_event_id,terminal_at,app_user_id,push_token_id,dispatch_lease_token,dispatch_leased_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '旧主题', '旧正文', 1, ?, ?, 3, ?, 'legacy-provider', ?, ?, ?, ?, ?, ?)",
                        id, f.tenantId(), f.projectId(), f.instanceId(), event, binding, recipient, channel,
                        channel.equals("PUSH") ? "PUSH" : channel.equals("WEBHOOK") ? "https://notify.example.com" : "ops@example.com", state, attempt, dueAt == null ? null : Timestamp.from(dueAt), UUID.randomUUID(),
                        state.equals("SUCCEEDED") ? Timestamp.from(NOW) : null, channel.equals("PUSH") ? UUID.randomUUID() : null,
                        channel.equals("PUSH") ? UUID.randomUUID() : null, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(30)));
            } else {
                execute(c, "INSERT INTO rule_notification_delivery(id,tenant_id,project_id,rule_id,rule_version_id,message_id,device_id,channel,recipient,subject,body,status,trace_id,attempt_count,max_attempts,next_attempt_at,provider_message_id,last_outbox_event_id,terminal_at,delivered_at,dispatch_lease_token,dispatch_leased_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ops@example.com', '旧主题', '旧正文', ?, 'notification-upgrade', ?, 3, ?, 'legacy-provider', ?, ?, ?, ?, ?)",
                        id, f.tenantId(), f.projectId(), f.ruleId(), f.ruleVersionId(), UUID.randomUUID(), f.deviceId(), channel, state, attempt,
                        dueAt == null ? null : Timestamp.from(dueAt), UUID.randomUUID(), state.equals("DELIVERED") ? Timestamp.from(NOW) : null,
                        state.equals("DELIVERED") ? Timestamp.from(NOW) : null, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(30)));
            }
            execute(c, "UPDATE " + domain.table() + " SET created_at=?,updated_at=? WHERE id=?", Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW.minusSeconds(60)), id);
            c.commit();
        }
        return id;
    }

    /** 两域公开claim真实持久化租约；不手工伪造有效令牌替代正例。 */
    private List<Claim> claim(Connection c, Domain domain, UUID token) throws SQLException {
        List<Claim> result = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement("SELECT id,next_attempt_no FROM claim_" + domain.prefix + "_notification_retries(?,100,60)")) {
            q.setQueryTimeout(5); q.setObject(1, token);
            try (ResultSet r = q.executeQuery()) { while (r.next()) result.add(new Claim(r.getObject(1, UUID.class), r.getInt(2))); }
        }
        return result;
    }

    /** 六参窄接口保持Java将使用的顺序，APP通过真实受限函数调用。 */
    private boolean stop(Connection c, Domain domain, UUID tenant, UUID project, UUID id, UUID token, Integer attempt, Instant now) throws SQLException {
        return Boolean.parseBoolean(text(c, "SELECT " + domain.function() + "(?,?,?,?,?,?)::text", tenant, project, id, token, attempt, now == null ? null : Timestamp.from(now)));
    }

    /** 完整row_to_json来自owner，既不受RLS过滤也不省略业务字段。 */
    private JsonNode row(Domain domain, UUID id) throws SQLException {
        try (Connection owner = ownerConnection()) { return mapper.readTree(text(owner, "SELECT row_to_json(d)::text FROM " + domain.table() + " d WHERE id=?", id)); }
    }

    /** 迁移前后及拒绝路径同时比较两域投递事实，不能只看命中行数量。 */
    private Map<String, List<String>> facts(UUID projectId) throws SQLException {
        Map<String, List<String>> facts = new LinkedHashMap<>();
        try (Connection owner = ownerConnection()) {
            for (Domain domain : Domain.values()) {
                List<String> rows = new ArrayList<>();
                try (PreparedStatement q = owner.prepareStatement("SELECT row_to_json(d)::text FROM " + domain.table() + " d WHERE project_id=? ORDER BY id")) {
                    q.setQueryTimeout(5); q.setObject(1, projectId);
                    try (ResultSet r = q.executeQuery()) { while (r.next()) rows.add(r.getString(1)); }
                }
                facts.put(domain.table(), List.copyOf(rows));
            }
        }
        return facts;
    }

    /** 精确旧/新target加载原全部Flyway文件，不让未来迁移隐蔽当前升级失败。 */
    private Flyway flyway(String target) { return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(target).load(); }
    /** 真实APP事务与局部RLS范围，禁止owner替代授权检查。 */
    private Connection appConnection(UUID projectId) throws SQLException { Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD); c.setAutoCommit(false); text(c, "SELECT set_config('app.project_id',?,true)", projectId.toString()); return c; }
    /** 只供fixture及独立读数，业务函数始终APP执行。 */
    private Connection ownerConnection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    /** 有界参数化单行查询。 */
    private String text(Connection c, String sql, Object... values) throws SQLException { try (PreparedStatement q = c.prepareStatement(sql)) { q.setQueryTimeout(5); for (int i=0;i<values.length;i++) q.setObject(i+1,values[i]); try (ResultSet r=q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); } } }
    /** ACL/RLS计数，不用它替代持久全行比较。 */
    private int integer(Connection c, String sql, Object... values) throws SQLException { return Integer.parseInt(text(c,sql,values)); }
    /** 写SQL仅限专库自有夹具或真实APP拒绝验收，不关闭约束和原触发器。 */
    private void execute(Connection c,String sql,Object... values) throws SQLException { try (PreparedStatement q=c.prepareStatement(sql)) { q.setQueryTimeout(5); for(int i=0;i<values.length;i++) q.setObject(i+1,values[i]); q.executeUpdate(); } }
    /** 两个独立域共享同一窄CAS规范，名称只来自固定枚举白名单。 */
    private enum Domain {
        /** 告警域，包含保持兼容的PUSH。 */ ALARM("alarm"),
        /** 规则域，APP没有表级UPDATE。 */ RULE("rule");
        /** SQL对象固定域前缀。 */ private final String prefix;
        /** 固定白名单构造。 */ Domain(String prefix) { this.prefix=prefix; }
        /** 域投递表。 */ String table() { return prefix+"_notification_delivery"; }
        /** 域受限停止函数。 */ String function() { return prefix+"_notification_stop_frozen_retry"; }
    }
    /** 真实claim最小结果，第三次SENDING必须仍是3。 */
    private record Claim(UUID id,int attempt) { }
    /** 所有通知表外键祖先，均真实插入旧schema。 */
    private record Fixture(UUID tenantId,UUID projectId,UUID accountId,UUID typeId,UUID deviceId,UUID alarmRuleId,UUID instanceId,UUID groupId,UUID recipientId,UUID templateId,UUID bindingId,UUID ruleId,UUID ruleVersionId) { }
}
