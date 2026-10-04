package com.things.link.bootstrap.rule;

import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.DebugMessageRuleCommand;
import com.things.link.rule.application.MessageRuleDebugResult;
import com.things.link.rule.application.MessageRuleDebugService;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.MessageRuleVersionView;
import com.things.link.rule.application.MessageRuleView;
import com.things.link.rule.application.ReviseMessageRuleCommand;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import com.things.link.rule.application.queue.RuleExecutionLogEntry;
import com.things.link.rule.application.queue.RuleExecutionLogStore;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.PublishedRuleStep;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** S8-1B 真实 PostgreSQL + Worker 验收：版本事实、RLS、跨租户归属与审计形成无 UI 闭环。 */
class MessageRuleIntegrationTests extends AbstractIntegrationTest {

    /** 无 UI 规则应用服务。 */
    @Autowired
    private MessageRuleService service;
    /** 无副作用样例执行服务。 */
    @Autowired
    private MessageRuleDebugService debugService;
    /** 真实 PostgreSQL 元数据、RLS 和事实断言入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 真实受限函数背后的持久规则执行回执。 */
    @Autowired
    private RuleExecutionReceiptStore receiptStore;
    /** 无请求上下文的数据面只能通过受限投影绑定生产规则版本。 */
    @Autowired
    private PublishedRuleCatalog publishedRuleCatalog;
    /** 生产规则执行的最小只追加日志。 */
    @Autowired
    private RuleExecutionLogStore executionLogStore;

    /** ThreadLocal 项目范围不得泄漏到其他集成测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** V0200 必须完整创建两张带注释、RLS 的表，并从应用角色撤销版本改写权限。 */
    @Test
    @Transactional
    void migrationCreatesCommentedRlsFactsAndImmutableVersionPrivileges() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relname IN ('rule_message', 'rule_version') AND relrowsecurity
                 ORDER BY relname
                """, String.class)).containsExactly("rule_message", "rule_version");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns AS column_info
                 WHERE column_info.table_schema = 'public'
                   AND column_info.table_name IN ('rule_message', 'rule_version')
                   AND col_description(
                       (quote_ident(column_info.table_schema) || '.' || quote_ident(column_info.table_name))::regclass,
                       column_info.ordinal_position) IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_version', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_version', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_version', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_version', 'DELETE')", Boolean.class)).isFalse();
    }

    /** 相同消息与不可变版本只能成功执行一次；更高 attempt 只有释放后才能重新抢占。 */
    @Test
    @Transactional
    void executionReceiptMakesRetryAndReplayIdempotent() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);
        MessageRuleView rule = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "queue-receipt-" + UUID.randomUUID(), "回执", "input => input"));
        MessageRuleVersionView version = service.versions(fixture.projectId(), rule.id()).getFirst();
        service.activate(fixture.projectId(), rule.id(), version.id(), rule.version());
        UUID messageId = Uuid7.generate();
        RuleMessage message = new RuleMessage(messageId, fixture.ownerTenantId(), fixture.projectId(),
                Uuid7.generate(), "trace-receipt", Instant.now(), "PROPERTY",
                new ObjectMapper().createObjectNode().put("value", 1), Map.of());
        RuleExecutionKey key = new RuleExecutionKey(fixture.projectId(), messageId, rule.id(), version.id());
        RuleExecutionEnvelope first = new RuleExecutionEnvelope(key, fixture.ownerTenantId(), message, 1, Instant.now());

        assertThat(receiptStore.tryClaim(first)).isEqualTo(com.things.link.rule.application.queue.RuleExecutionReceiptClaim.ACQUIRED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT plan_version_ids[1] FROM rule_execution_receipt
                 WHERE project_id = ? AND message_id = ?
                """, UUID.class, fixture.projectId(), messageId)).isEqualTo(version.id());
        MessageRuleView laterRule = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "later-active-" + UUID.randomUUID(), null, "input => ({later: true})"));
        MessageRuleVersionView laterVersion = service.versions(fixture.projectId(), laterRule.id()).getFirst();
        service.activate(fixture.projectId(), laterRule.id(), laterVersion.id(), laterRule.version());
        // 同一 messageId 必须恢复首次 claim 的单版本计划，不能把后来发布的规则混入在途消息。
        assertThat(publishedRuleCatalog.resolve(
                fixture.ownerTenantId(), fixture.projectId(), messageId).steps())
                .extracting(PublishedRuleStep::ruleVersionId).containsExactly(version.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT rule_execution_receipt_claim_state(?, ?, ?, ?, ?, ?, ?, ?)
                """, String.class, fixture.ownerTenantId(), fixture.projectId(), messageId,
                rule.id(), version.id(), new UUID[]{version.id(), laterVersion.id()}, 1,
                java.sql.Timestamp.from(Instant.now())))
                .isEqualTo("BUSY");
        assertThat(receiptStore.tryClaim(first)).isEqualTo(com.things.link.rule.application.queue.RuleExecutionReceiptClaim.BUSY);
        jdbcTemplate.update("""
                UPDATE rule_execution_receipt SET lease_until = clock_timestamp() - interval '1 second'
                 WHERE project_id = ? AND message_id = ? AND rule_version_id = ?
                """, fixture.projectId(), messageId, version.id());
        assertThat(receiptStore.tryClaim(first))
                .isEqualTo(com.things.link.rule.application.queue.RuleExecutionReceiptClaim.ACQUIRED);
        receiptStore.release(first);
        RuleExecutionEnvelope retry = first.nextAttempt(Instant.now());
        assertThat(receiptStore.tryClaim(retry)).isEqualTo(com.things.link.rule.application.queue.RuleExecutionReceiptClaim.ACQUIRED);
        receiptStore.complete(retry);
        assertThat(receiptStore.tryClaim(retry)).isEqualTo(com.things.link.rule.application.queue.RuleExecutionReceiptClaim.COMPLETED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rule_execution_receipt
                 WHERE project_id = ? AND message_id = ? AND rule_version_id = ?
                """, Integer.class, fixture.projectId(), messageId, version.id())).isEqualTo(1);
    }

    /** ACTIVE 计划核对 owner tenant 并按创建顺序稳定返回；暂停规则不能进入新消息。 */
    @Test
    @Transactional
    void publishedCatalogReturnsOnlyTrustedActiveImmutableVersionsInStableOrder() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);
        MessageRuleView first = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "active-first-" + UUID.randomUUID(), null, "input => ({order: 1})"));
        MessageRuleVersionView firstVersion = service.versions(fixture.projectId(), first.id()).getFirst();
        first = service.activate(fixture.projectId(), first.id(), firstVersion.id(), first.version());
        MessageRuleView second = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "active-second-" + UUID.randomUUID(), null, "input => ({order: 2})"));
        MessageRuleVersionView secondVersion = service.versions(fixture.projectId(), second.id()).getFirst();
        second = service.activate(fixture.projectId(), second.id(), secondVersion.id(), second.version());

        UUID firstMessageId = Uuid7.generate();
        List<PublishedRuleStep> plan = publishedRuleCatalog.resolve(
                fixture.ownerTenantId(), fixture.projectId(), firstMessageId).steps();
        assertThat(plan).extracting(PublishedRuleStep::ruleId)
                .containsExactly(first.id(), second.id());
        assertThat(plan).extracting(PublishedRuleStep::ruleVersionId)
                .containsExactly(firstVersion.id(), secondVersion.id());
        assertThat(publishedRuleCatalog.resolve(
                fixture.collaboratorTenantId(), fixture.projectId(), Uuid7.generate()).steps()).isEmpty();

        service.pause(fixture.projectId(), first.id(), first.version());
        assertThat(publishedRuleCatalog.resolve(
                fixture.ownerTenantId(), fixture.projectId(), Uuid7.generate()).steps())
                .extracting(PublishedRuleStep::ruleId).containsExactly(second.id());
        // 已构造计划持有不可变源码快照，暂停只影响下一条 normalized 消息，不改写当前信封。
        assertThat(plan.getFirst().source()).isEqualTo("input => ({order: 1})");
    }

    /** V0240 生产日志必须带 RLS、全列注释、只追加权限并按 attempt 吸收重复完成通知。 */
    @Test
    @Transactional
    void productionExecutionLogIsRestrictedMinimalAndAttemptIdempotent() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);
        MessageRuleView rule = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "execution-log-" + UUID.randomUUID(), null, "input => input"));
        MessageRuleVersionView version = service.versions(fixture.projectId(), rule.id()).getFirst();
        RuleExecutionKey key = new RuleExecutionKey(
                fixture.projectId(), Uuid7.generate(), rule.id(), version.id());
        RuleExecutionLogEntry entry = new RuleExecutionLogEntry(
                fixture.ownerTenantId(), key, 1, RuleExecutionLogEntry.Status.SUCCESS, "SUCCESS",
                Duration.ofMillis(7), 12, 13, Instant.now());

        assertThat(executionLogStore.append(entry)).isTrue();
        assertThat(executionLogStore.append(entry)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'rule_execution_log'", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns AS column_info
                 WHERE column_info.table_schema = 'public'
                   AND column_info.table_name = 'rule_execution_log'
                   AND col_description(
                       (quote_ident(column_info.table_schema) || '.' || quote_ident(column_info.table_name))::regclass,
                       column_info.ordinal_position) IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_execution_log', 'UPDATE')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_execution_log', 'DELETE')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, result_code, duration_millis, input_bytes, output_bytes
                  FROM rule_execution_log WHERE message_id = ?
                """, key.messageId())).containsEntry("status", "SUCCESS")
                .containsEntry("result_code", "SUCCESS")
                .containsEntry("duration_millis", 7L)
                .containsEntry("input_bytes", 12)
                .containsEntry("output_bytes", 13);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'rule_execution_log'
                   AND column_name IN ('payload', 'source', 'exception', 'error_message')
                """, Integer.class)).isZero();
    }

    /** V0210 必须创建带项目 RLS、全列注释、无通用删除权限的短保留调试事实。 */
    @Test
    @Transactional
    void migrationCreatesCommentedRlsDebugFactsAndRestrictedCleanup() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT relrowsecurity FROM pg_class WHERE relname = 'rule_debug_event'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns AS column_info
                 WHERE column_info.table_schema = 'public'
                   AND column_info.table_name = 'rule_debug_event'
                   AND col_description(
                       (quote_ident(column_info.table_schema) || '.' || quote_ident(column_info.table_name))::regclass,
                       column_info.ordinal_position) IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_debug_event', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('thingslink_app', 'rule_debug_event', 'DELETE')", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_function_privilege(
                    'thingslink_app', 'rule_debug_event_delete_expired(integer)', 'EXECUTE')
                """, Boolean.class)).isTrue();
    }

    /** 创建、追加、发布、回滚、暂停与恢复只移动定义指针，两个源码版本和六条审计均永久保留。 */
    @Test
    @Transactional
    void lifecycleKeepsVersionsImmutableAndAuditsEveryMutation() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);

        MessageRuleView created = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "温度清洗", "统一摄氏温度", "input => ({temperature: input.temperature})"));
        List<MessageRuleVersionView> firstHistory = service.versions(fixture.projectId(), created.id());
        MessageRuleView revised = service.revise(fixture.projectId(), created.id(),
                new ReviseMessageRuleCommand(
                        "温度清洗", "统一摄氏温度并标记版本",
                        "input => ({temperature: input.temperature, version: 2})", created.version()));
        List<MessageRuleVersionView> history = service.versions(fixture.projectId(), created.id());

        MessageRuleView firstPublished = service.activate(
                fixture.projectId(), created.id(), firstHistory.getFirst().id(), revised.version());
        MessageRuleView secondPublished = service.activate(
                fixture.projectId(), created.id(), history.getLast().id(), firstPublished.version());
        MessageRuleView paused = service.pause(
                fixture.projectId(), created.id(), secondPublished.version());
        MessageRuleView resumed = service.activate(
                fixture.projectId(), created.id(), history.getLast().id(), paused.version());

        assertThat(history).extracting(MessageRuleVersionView::versionNumber).containsExactly(1L, 2L);
        assertThat(history).extracting(MessageRuleVersionView::source).containsExactly(
                "input => ({temperature: input.temperature})",
                "input => ({temperature: input.temperature, version: 2})");
        assertThat(firstPublished.activeVersionId()).isEqualTo(firstHistory.getFirst().id());
        assertThat(secondPublished.activeVersionId()).isEqualTo(history.getLast().id());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.version()).isEqualTo(5L);
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(resumed.activeVersionId()).isEqualTo(history.getLast().id());
        assertThat(resumed.version()).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM rule_message WHERE id = ?", UUID.class, created.id()))
                .isEqualTo(fixture.ownerTenantId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE project_id = ? AND target_type = 'message_rule' AND target_id = ?
                """, Integer.class, fixture.projectId(), created.id())).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE project_id = ? AND details::text LIKE '%input.temperature%'
                """, Integer.class, fixture.projectId())).isZero();
    }

    /** app.project_id 必须同时隔离定义与版本；切换项目后不能看见另一个项目的任一行。 */
    @Test
    @Transactional
    void projectRlsHidesDefinitionsAndVersionsTogether() {
        Fixture first = seedCollaboratorFixture();
        Fixture second = seedCollaboratorFixture();
        selectScope(first);
        MessageRuleView firstRule = service.create(first.projectId(),
                new CreateMessageRuleCommand("first", null, "input => input"));
        selectScope(second);
        MessageRuleView secondRule = service.create(second.projectId(),
                new CreateMessageRuleCommand("second", null, "input => input"));

        selectScope(first);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM rule_message", UUID.class))
                .containsExactly(firstRule.id());
        assertThat(jdbcTemplate.queryForList("SELECT rule_id FROM rule_version", UUID.class))
                .containsExactly(firstRule.id());
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM rule_message WHERE id = ?", UUID.class, secondRule.id())).isEmpty();
    }

    /** 真实 Worker 只执行指定版本，数据库摘要遮蔽凭据且保留原始字节数和七天截止时刻。 */
    @Test
    @Transactional
    void debugExecutionPersistsSanitizedExpiringFactWithoutChangingRule() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);
        MessageRuleView rule = service.create(fixture.projectId(), new CreateMessageRuleCommand(
                "调试规则", null,
                "input => ({value: input.value + 1, password: input.password})"));
        MessageRuleVersionView version = service.versions(fixture.projectId(), rule.id()).getFirst();

        MessageRuleDebugResult result = debugService.execute(fixture.projectId(),
                new DebugMessageRuleCommand(
                        rule.id(), version.id(), """
                                {"value":41,"password":"device-secret"}
                                """));

        assertThat(result.status().name())
                .as("调试沙箱固定错误码=%s，完整耗时=%dms", result.resultCode(), result.duration().toMillis())
                .isEqualTo("SUCCESS");
        assertThat(result.outputJson()).contains("device-secret");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, result_code, input_summary, output_summary,
                       input_bytes, output_bytes,
                       extract(epoch FROM (expires_at - created_at))::bigint AS retention_seconds
                  FROM rule_debug_event WHERE id = ?
                """, result.eventId())).satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("SUCCESS");
                    assertThat(row.get("result_code")).isEqualTo("SUCCESS");
                    assertThat(row.get("input_summary").toString())
                            .contains("[REDACTED]").doesNotContain("device-secret");
                    assertThat(row.get("output_summary").toString())
                            .contains("[REDACTED]").doesNotContain("device-secret");
                    assertThat(((Number) row.get("input_bytes")).intValue()).isPositive();
                    assertThat(((Number) row.get("output_bytes")).intValue()).isPositive();
                    assertThat(((Number) row.get("retention_seconds")).longValue()).isEqualTo(604_800L);
                });
        assertThat(service.get(fixture.projectId(), rule.id()).version()).isEqualTo(1L);
    }

    /** 到期函数只能限批删除，未到期事实必须保留，证明保留不是仅存在于配置里的承诺。 */
    @Test
    @Transactional
    void cleanupDeletesOnlyExpiredEventsInBoundedBatches() {
        Fixture fixture = seedCollaboratorFixture();
        selectScope(fixture);
        MessageRuleView rule = service.create(fixture.projectId(),
                new CreateMessageRuleCommand("清理规则", null, "input => input"));
        MessageRuleVersionView version = service.versions(fixture.projectId(), rule.id()).getFirst();
        MessageRuleDebugResult current = debugService.execute(fixture.projectId(),
                new DebugMessageRuleCommand(rule.id(), version.id(), "{}"));
        jdbcTemplate.update("""
                INSERT INTO rule_debug_event (
                    id, tenant_id, project_id, rule_id, version_id, status, result_code,
                    duration_millis, input_bytes, output_bytes, input_summary, output_summary,
                    created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', 'SUCCESS', 1, 2, 2, '{}', '{}', ?,
                        now() - interval '8 days', now() - interval '1 day')
                """, Uuid7.generate(), fixture.ownerTenantId(), fixture.projectId(), rule.id(), version.id(),
                fixture.collaboratorAccountId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT rule_debug_event_delete_expired(1)", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM rule_debug_event", UUID.class)).containsExactly(current.eventId());
    }

    /** @return 项目 owner tenant 与管理员调用者 tenant 故意不同的真实协作夹具 */
    private Fixture seedCollaboratorFixture() {
        UUID ownerTenantId = Uuid7.generate();
        UUID collaboratorTenantId = Uuid7.generate();
        UUID ownerAccountId = Uuid7.generate();
        UUID collaboratorAccountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)",
                ownerTenantId, "S8 owner tenant");
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)",
                collaboratorTenantId, "S8 collaborator tenant");
        insertAccount(ownerAccountId, "owner");
        insertAccount(collaboratorAccountId, "collaborator");
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, 'S8 rule project', 'sh-1', ?)
                """, projectId, ownerTenantId, projectKey(projectId));
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'OWNER'), (?, ?, ?, 'ADMIN')
                """, Uuid7.generate(), projectId, ownerAccountId,
                Uuid7.generate(), projectId, collaboratorAccountId);
        return new Fixture(
                ownerTenantId, collaboratorTenantId, projectId, collaboratorAccountId);
    }

    /** 插入不会与其他测试冲突的账号。 */
    private void insertAccount(UUID accountId, String prefix) {
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S8 rule account')
                """, accountId, "s8-rule-" + prefix + "-" + accountId + "@example.com");
    }

    /** 同时设置 Java 与当前事务连接的项目范围，模拟已认证协作者请求。 */
    private void selectScope(Fixture fixture) {
        TenantContext.set(new TenantScope(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.collaboratorAccountId()));
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, fixture.collaboratorTenantId().toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)",
                String.class, fixture.projectId().toString());
    }

    /** @return 满足项目键约束的唯一短标识 */
    private static String projectKey(UUID projectId) {
        return "s8r" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** 测试项目 owner、协作者与隔离轴。 */
    private record Fixture(
            UUID ownerTenantId,
            UUID collaboratorTenantId,
            UUID projectId,
            UUID collaboratorAccountId) {
    }
}
