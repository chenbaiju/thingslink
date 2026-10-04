package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectUsageFactRecorder;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真实 PostgreSQL/TimescaleDB 上验证 S7-5 绝对日用量归并、单调 UPSERT 与可信决策投影。
 */
@Transactional
class DailyUsageReconciliationIntegrationTests extends AbstractIntegrationTest {

    /** 真实 JDBC 访问器。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 今天与昨天的归并事务服务。 */
    @Autowired
    private DailyUsageReconciliationService reconciliationService;
    /** 绝对账单仓储，用于验证较小重算值不能回退事实。 */
    @Autowired
    private DailyUsageReconciliationRepository reconciliationRepository;
    /** 运行时可信项目日额度决策服务。 */
    @Autowired
    private ProjectDailyQuotaDecisionService decisionService;
    /** REST 没有既有业务表，需由受限端口保存幂等追加事实。 */
    @Autowired
    private ProjectUsageFactRecorder usageFactRecorder;

    /** 本测试唯一租户。 */
    private final UUID tenantId = Uuid7.generate();
    /** 本测试唯一项目。 */
    private final UUID projectId = Uuid7.generate();
    /** 命令 requested_by 外键账号。 */
    private final UUID accountId = Uuid7.generate();

    /** 在首个连接借出前写入 RLS ThreadLocal，测试事务结束自动回滚全部夹具。 */
    @BeforeEach
    void setScope() {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
    }

    /** 防止共享测试线程把本用例范围带入下一个测试类。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 短租约不得重复领取；今天/昨天真实事实都应归并，较小快照不得回退。 */
    @Test
    void reconcilesTelemetryFactsAndReturnsDegradedDecision() {
        LocalDate usageDate = LocalDate.now(ZoneOffset.UTC);
        Instant now = usageDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
        seedProjectAndPolicy();
        SeedDevice device = seedDeviceAndCommandDefinition();
        seedTelemetryFacts(device, now);
        String restEventKey = "s7-rest-" + Uuid7.generate();
        assertThat(usageFactRecorder.record(
                tenantId, projectId, QuotaMetric.REST_API_CALL, restEventKey, now)).isTrue();
        assertThat(usageFactRecorder.record(
                tenantId, projectId, QuotaMetric.REST_API_CALL, restEventKey, now.plusSeconds(1))).isTrue();
        DailyUsageScope scope = new DailyUsageScope(tenantId, projectId);
        com.things.link.project.domain.DailyUsageScope domainScope =
                new com.things.link.project.domain.DailyUsageScope(tenantId, projectId);

        assertThat(reconciliationRepository.claimDueScopes(100)).contains(domainScope);
        assertThat(reconciliationRepository.claimDueScopes(100)).doesNotContain(domainScope);
        reconciliationService.reconcile(scope);

        Map<String, Long> usage = jdbcTemplate.query("""
                        SELECT metric, used_value
                          FROM sys_usage_counter_daily
                         WHERE tenant_id = ? AND project_id = ? AND usage_date = ?
                        """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("metric"), resultSet.getLong("used_value")),
                tenantId, projectId, usageDate).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(usage).containsEntry("UPLINK_MESSAGE", 2L)
                .containsEntry("UPLINK_BYTES", 50L)
                .containsEntry("DOWNLINK_MESSAGE", 1L)
                .containsEntry("TIME_SERIES_POINT", 3L)
                .containsEntry("REST_API_CALL", 1L);
        Map<String, Long> yesterdayUsage = jdbcTemplate.query("""
                        SELECT metric, used_value
                          FROM sys_usage_counter_daily
                         WHERE tenant_id = ? AND project_id = ? AND usage_date = ?
                        """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("metric"), resultSet.getLong("used_value")),
                tenantId, projectId, usageDate.minusDays(1)).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(yesterdayUsage).containsEntry("UPLINK_MESSAGE", 1L)
                .containsEntry("UPLINK_BYTES", 5L)
                .containsEntry("TIME_SERIES_POINT", 1L);

        reconciliationRepository.mergeAbsolute(domainScope, usageDate,
                new com.things.link.project.domain.DailyUsageValue(
                        com.things.link.project.domain.QuotaMetric.UPLINK_MESSAGE, 1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT used_value FROM sys_usage_counter_daily
                 WHERE tenant_id = ? AND project_id = ? AND usage_date = ? AND metric = 'UPLINK_MESSAGE'
                """, Long.class, tenantId, projectId, usageDate)).isEqualTo(2L);
        assertThat(decisionService.decideTrustedProject(tenantId, projectId, QuotaMetric.UPLINK_MESSAGE))
                .isEqualTo(QuotaStatus.DEGRADED);
    }

    /** 计量只认事实与方向、不认协议：接入面 HTTP／CoAP 上报与 MQTT 同样计入日用量。 */
    @Test
    void countsAccessPlaneFactsWithSameRulesAsMqtt() {
        LocalDate usageDate = LocalDate.now(ZoneOffset.UTC);
        Instant now = usageDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
        seedProjectAndPolicy();
        SeedDevice device = seedDeviceAndCommandDefinition();
        UUID httpMessageId = Uuid7.generate();
        UUID coapMessageId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                httpMessageId, projectId, Timestamp.from(now));
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                coapMessageId, projectId, Timestamp.from(now.plusSeconds(1)));
        insertMessageLog(device.deviceId(), httpMessageId, 41, now, "HTTP");
        insertMessageLog(device.deviceId(), coapMessageId, 9, now.plusSeconds(1), "COAP");

        reconciliationService.reconcile(new DailyUsageScope(tenantId, projectId));

        Map<String, Long> usage = jdbcTemplate.query("""
                        SELECT metric, used_value
                          FROM sys_usage_counter_daily
                         WHERE tenant_id = ? AND project_id = ? AND usage_date = ?
                        """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("metric"), resultSet.getLong("used_value")),
                tenantId, projectId, usageDate).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(usage).as("两条接入面上行按同一口径计入消息数与原始字节数")
                .containsEntry("UPLINK_MESSAGE", 2L)
                .containsEntry("UPLINK_BYTES", 50L);
    }

    /** 创建 active tenant/project 与 1 条上行日额度的独立策略模板。 */
    private void seedProjectAndPolicy() {
        UUID policyId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_quota_policy (id, code, uplink_message_daily_limit)
                VALUES (?, ?, 1)
                """, policyId, "S7U" + policyId.toString().replace("-", "").substring(0, 20));
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name, quota_policy_id) VALUES (?, ?, ?)",
                tenantId, "S7-5 日用量租户", policyId);
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S7-5 计量账号')
                """, accountId, "s7-usage-" + accountId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, 'S7-5 日用量项目', 'sh-1', ?)
                """, projectId, tenantId,
                "s7usage" + projectId.toString().replace("-", "").substring(0, 16));
        configureDatabaseScope();
    }

    /** 创建设备类型、设备和命令定义，以满足命令事实的完整外键约束。 */
    private SeedDevice seedDeviceAndCommandDefinition() {
        UUID typeId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        UUID commandDefinitionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO dev_type
                    (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                VALUES (?, ?, ?, 's7_usage_type', 'S7 计量设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')
                """, typeId, tenantId, projectId);
        jdbcTemplate.update("""
                INSERT INTO dev_device
                    (id, tenant_id, project_id, device_type_id, device_key, name, status)
                VALUES (?, ?, ?, ?, 's7_usage_device', 'S7 计量设备', 'ONLINE')
                """, deviceId, tenantId, projectId, typeId);
        jdbcTemplate.update("""
                INSERT INTO dev_command_definition
                    (id, tenant_id, project_id, device_type_id, command_key, name,
                     input_schema, output_schema, timeout_seconds)
                VALUES (?, ?, ?, ?, 'restart', '重启', '{}'::jsonb, '{}'::jsonb, 30)
                """, commandDefinitionId, tenantId, projectId, typeId);
        return new SeedDevice(deviceId, commandDefinitionId);
    }

    /** 写入今天两条上行/50 字节/三点/一命令，并写入昨天一条上行/5 字节/一点。 */
    private void seedTelemetryFacts(SeedDevice device, Instant now) {
        UUID firstMessageId = Uuid7.generate();
        UUID secondMessageId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                firstMessageId, projectId, Timestamp.from(now));
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                secondMessageId, projectId, Timestamp.from(now.plusSeconds(1)));
        insertMessageLog(device.deviceId(), firstMessageId, 37, now);
        insertMessageLog(device.deviceId(), secondMessageId, 13, now.plusSeconds(1));
        insertPoint(device.deviceId(), firstMessageId, "temperature", now, 23.5);
        insertPoint(device.deviceId(), firstMessageId, "humidity", now, 55.0);
        insertPoint(device.deviceId(), secondMessageId, "pressure", now.plusSeconds(1), 1001.0);
        UUID yesterdayMessageId = Uuid7.generate();
        Instant yesterday = now.minusSeconds(86_400);
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                yesterdayMessageId, projectId, Timestamp.from(yesterday));
        insertMessageLog(device.deviceId(), yesterdayMessageId, 5, yesterday);
        insertPoint(device.deviceId(), yesterdayMessageId, "temperature", yesterday, 22.0);
        jdbcTemplate.update("""
                INSERT INTO ts_device_command
                    (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                     command_key, request_payload, status, idempotency_key, requested_by, timeout_seconds,
                     attempt_count, max_attempts, next_attempt_at, trace_id, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, 'restart', '{}'::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3, ?, ?, ?)
                """, Uuid7.generate(), tenantId, projectId, device.deviceId(), device.deviceId(),
                device.commandDefinitionId(), "s7-usage-command-" + Uuid7.generate(), accountId,
                Timestamp.from(now), "s7-usage-trace", Timestamp.from(now));
    }

    /** 写一条上行消息日志，raw_bytes 保留解码前真实字节数。 */
    private void insertMessageLog(UUID deviceId, UUID messageId, int rawBytes, Instant receivedAt) {
        insertMessageLog(deviceId, messageId, rawBytes, receivedAt, "MQTT");
    }

    /** 写一条指定协议的上行消息日志，用于证明计量不因接入协议不同而改变。 */
    private void insertMessageLog(UUID deviceId, UUID messageId, int rawBytes, Instant receivedAt, String protocol) {
        jdbcTemplate.update("""
                INSERT INTO ts_device_message_log
                    (id, project_id, device_id, message_id, tenant_id, protocol, direction,
                     raw_bytes, ts, received_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, 'UP', ?, ?, ?, 's7-usage-trace')
                """, Uuid7.generate(), projectId, deviceId, messageId, tenantId, protocol, rawBytes,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt));
    }

    /** 写一个满足单值约束的时序点。 */
    private void insertPoint(UUID deviceId, UUID messageId, String propertyKey, Instant occurredAt, double value) {
        jdbcTemplate.update("""
                INSERT INTO ts_property_point
                    (project_id, device_id, property_key, ts, message_id, value_double)
                VALUES (?, ?, ?, ?, ?, ?)
                """, projectId, deviceId, propertyKey, Timestamp.from(occurredAt), messageId, value);
    }

    /** 将测试事务的物理连接绑定到当前 tenant/project，供全部业务表 RLS 使用。 */
    private void configureDatabaseScope() {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                tenantId.toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                projectId.toString());
    }

    /** 命令外键所需的设备与命令定义 ID。 */
    private record SeedDevice(UUID deviceId, UUID commandDefinitionId) {
    }
}
