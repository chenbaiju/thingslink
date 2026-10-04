package com.things.link.bootstrap.telemetry.overview;

import com.things.link.alarm.application.ProjectAlarmStatistics;
import com.things.link.alarm.application.ProjectAlarmStatisticsService;
import com.things.link.device.application.ProjectDeviceStatisticsService;
import com.things.link.device.domain.DeviceStatisticsRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.OverviewCache;
import com.things.link.telemetry.application.OverviewCacheMetrics;
import com.things.link.telemetry.application.OverviewService;
import com.things.link.telemetry.domain.MessageLogRepository;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S5-3 概要统计的真实 PostgreSQL + Redis 派生缓存验收。 */
class ProjectOverviewCacheIntegrationTests extends AbstractIntegrationTest {
    /** 本测试的租户隔离轴。 */
    private final UUID tenantId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    /** 本测试的项目隔离轴。 */
    private final UUID projectId = UUID.randomUUID();
    /** 用于验证在线计数变化的设备。 */
    private final UUID inactiveDeviceId = UUID.randomUUID();
    /** device 域的 PostgreSQL 统计端口。 */
    @Autowired private DeviceStatisticsRepository deviceStatisticsRepository;
    /** telemetry 域的 PostgreSQL 消息统计端口。 */
    @Autowired private MessageLogRepository messageLogRepository;
    /** 生产 Redis 概要缓存端口。 */
    @Autowired private OverviewCache overviewCache;
    /** 生产低基数缓存指标。 */
    @Autowired private OverviewCacheMetrics metrics;
    @Autowired private com.things.link.alarm.domain.AlarmInstanceRepository alarmRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 直接核对 TTL 与清理测试键。 */
    @Autowired private StringRedisTemplate redisTemplate;

    /** 每个测试后清理 ThreadLocal、Redis 和所有者角色写入的夹具。 */
    @AfterEach
    void cleanUp() throws Exception {
        TenantContext.clear();
        redisTemplate.delete(redisKey());
        try (Connection connection = ownerConnection()) {
            execute(connection, "DELETE FROM ts_device_message_log WHERE project_id = ?", projectId);
            execute(connection, "DELETE FROM alarm_instance WHERE project_id = ?", projectId);
            execute(connection, "DELETE FROM alarm_rule WHERE project_id = ?", projectId);
            execute(connection, "DELETE FROM dev_device WHERE project_id = ?", projectId);
            execute(connection, "DELETE FROM sys_project WHERE id = ?", projectId);
            execute(connection, "DELETE FROM sys_tenant WHERE id = ?", tenantId);
            execute(connection, "DELETE FROM sys_account WHERE id = ?", accountId);
        }
    }

    /**
     * 首次 miss 必须用真实事实生成 30 秒快照，命中期间数据变化不得绕过缓存，
     * 键删除后又必须回源并看到新事实。
     */
    @Test
    void cachesRealFactsAndFallsBackAfterExpiryBoundary() throws Exception {
        Instant now = Instant.now();
        prepareFacts(now);
        TenantContext.set(new TenantScope(tenantId, projectId, UUID.randomUUID()));
        OverviewService service = service();

        var first = service.get(projectId);
        assertThat(first.devices().total()).isEqualTo(3);
        assertThat(first.devices().online()).isEqualTo(1);
        assertThat(first.devices().active24h()).isEqualTo(2);
        assertThat(first.messages24h().count()).isEqualTo(1);
        assertThat(first.messages24h().bytes()).isEqualTo(10);
        assertThat(first.alarmRate().available()).isTrue();
        assertThat(first.alarmRate().value()).isZero();
        assertThat(redisTemplate.getExpire(redisKey())).isBetween(1L, 30L);

        mutateFacts(now);
        var cached = service.get(projectId);
        assertThat(cached.generatedAt()).isEqualTo(first.generatedAt());
        assertThat(cached.devices().online()).isEqualTo(1);
        assertThat(cached.messages24h().count()).isEqualTo(1);

        assertThat(redisTemplate.delete(redisKey())).isTrue();
        var refreshed = service.get(projectId);
        assertThat(refreshed.generatedAt()).isAfterOrEqualTo(first.generatedAt());
        assertThat(refreshed.devices().online()).isEqualTo(2);
        assertThat(refreshed.messages24h().count()).isEqualTo(2);
        assertThat(refreshed.messages24h().bytes()).isEqualTo(30);
    }

    @Test
    void highestActiveSeverityCountsEachLiveDeviceOnceAndExcludesOtherStates() throws Exception {
        prepareFacts(Instant.now());
        try (Connection c = ownerConnection()) {
            for (String severity : java.util.List.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO")) {
                UUID device = UUID.randomUUID();
                insertDevice(c, device, severity, "OFFLINE", null);
                insertAlarm(c, device, severity, "ACTIVE", "ACKNOWLEDGED");
                insertAlarm(c, device, severity, "ACTIVE", "UNACKNOWLEDGED");
                if (severity.equals("CRITICAL")) insertAlarm(c, device, "MAJOR", "ACTIVE", "UNACKNOWLEDGED");
            }
            insertAlarm(c, inactiveDeviceId, "CRITICAL", "PENDING", "UNACKNOWLEDGED");
            insertAlarm(c, inactiveDeviceId, "MAJOR", "CLEARED", "ACKNOWLEDGED");
            UUID deleted = UUID.randomUUID();
            insertDevice(c, deleted, "deleted", "OFFLINE", null);
            insertAlarm(c, deleted, "CRITICAL", "ACTIVE", "UNACKNOWLEDGED");
            execute(c, "UPDATE dev_device SET deleted_at=now() WHERE id=?", deleted);
        }
        TenantContext.set(new TenantScope(tenantId, projectId, UUID.randomUUID()));
        var result = service().get(projectId);
        assertThat(result.devices().total()).isEqualTo(8);
        assertThat(result.alarmSeverityDeviceCounts()).isEqualTo(
                new com.things.link.telemetry.application.OverviewSnapshot.AlarmSeverityDeviceCounts(3,1,1,1,1,1));
        assertThat(result.alarmRate().value()).isEqualTo(5D / 8);
        assertThat(overviewCache.find(projectId)).contains(result);
        var json = tools.jackson.databind.json.JsonMapper.builder().build()
                .valueToTree(com.things.link.telemetry.api.dto.response.OverviewResponse.from(result));
        assertThat(json.path("alarmSeverityDeviceCounts").path("NORMAL").asLong()).isEqualTo(3);
        assertThat(alarmRepository.activeDeviceSeverities(UUID.randomUUID(), null, 1000)).isEmpty();
    }

    @Test
    void repeatableReadKeepsDeviceTotalAndAlarmsOnTheSameSnapshot() throws Exception {
        prepareFacts(Instant.now());
        try (Connection c = ownerConnection()) {
            insertAlarm(c, inactiveDeviceId, "MAJOR", "ACTIVE", "UNACKNOWLEDGED");
        }
        TenantContext.set(new TenantScope(tenantId, projectId, UUID.randomUUID()));
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        var devices = org.mockito.Mockito.spy(new ProjectDeviceStatisticsService(deviceStatisticsRepository, projects));
        org.mockito.Mockito.doAnswer(call -> {
            Object result = call.callRealMethod();
            try (Connection c = ownerConnection()) {
                execute(c, "UPDATE dev_device SET deleted_at=now() WHERE id=?", inactiveDeviceId);
            }
            return result;
        }).when(devices).snapshot(org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.any());
        var before = service(devices, projects).get(projectId);
        assertThat(before.devices().total()).isEqualTo(3);
        assertThat(before.alarmSeverityDeviceCounts().major()).isEqualTo(1);
        redisTemplate.delete(redisKey());
        var after = service().get(projectId);
        assertThat(after.devices().total()).isEqualTo(2);
        assertThat(after.alarmSeverityDeviceCounts().normal()).isEqualTo(2);
        assertThat(after.alarmRate().value()).isZero();
    }

    @Test
    void corruptDistributionFallsBackAndEmptyProjectHasAllZeroCategories() throws Exception {
        prepareFacts(Instant.now());
        TenantContext.set(new TenantScope(tenantId, projectId, UUID.randomUUID()));
        var initial = service().get(projectId);
        String valid = redisTemplate.opsForValue().get(redisKey());
        assertThat(valid).contains("\"normal\":3");
        redisTemplate.opsForValue().set(redisKey(), valid.replace("\"normal\":3", "\"normal\":99"));
        assertThat(service().get(projectId).alarmSeverityDeviceCounts()).isEqualTo(initial.alarmSeverityDeviceCounts());
        try (Connection c = ownerConnection()) {
            execute(c, "UPDATE dev_device SET deleted_at=now() WHERE project_id=?", projectId);
        }
        redisTemplate.delete(redisKey());
        var empty = service().get(projectId);
        assertThat(empty.devices().total()).isZero();
        assertThat(empty.alarmSeverityDeviceCounts().total()).isZero();
        assertThat(empty.alarmRate().value()).isZero();
    }

    private void insertAlarm(Connection c, UUID device, String severity, String state, String ack) throws Exception {
        UUID rule = UUID.randomUUID();
        execute(c, """
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,
                    property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,?,?,?,'temperature','GT',30,'LT',25,?)
                """, rule, tenantId, projectId, rule.toString(), rule.toString(), device, severity);
        execute(c, """
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                    alarm_type,severity,condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,
                    cleared_at,clear_reason,acknowledged_at,acknowledged_by)
                VALUES (?,?,?,?,'DEVICE',?,?,?,?,?,now(),now(),now(),31,?,?,?,?)
                """, UUID.randomUUID(), tenantId, projectId, rule, device, rule.toString(), severity, state, ack,
                state.equals("CLEARED") ? Timestamp.from(Instant.now()) : null,
                state.equals("CLEARED") ? "AUTO_RECOVERY" : null,
                ack.equals("ACKNOWLEDGED") ? Timestamp.from(Instant.now()) : null,
                ack.equals("ACKNOWLEDGED") ? accountId : null);
    }

    /** 使用真实仓储和缓存装配应用服务，只替换与本测试无关的成员目录。 */
    private OverviewService service() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        var deviceStatistics = new ProjectDeviceStatisticsService(deviceStatisticsRepository, projects);
        return service(deviceStatistics, projects);
    }

    private OverviewService service(ProjectDeviceStatisticsService devices, ProjectService projects) {
        var alarms = new ProjectAlarmStatisticsService(alarmRepository, projects, devices);
        var target = new OverviewService(devices, messageLogRepository, alarms, projects, overviewCache, metrics);
        var factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (OverviewService) factory.getProxy();
    }

    /** 建立 3 台设备与窗口内外消息，同时证明消息口径使用 received_at 而非 ts。 */
    private void prepareFacts(Instant now) throws Exception {
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test-only','overview')",
                    accountId, accountId + "@example.test");
            execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'S5 overview tenant')", tenantId);
            execute(connection, """
                    INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                    VALUES (?, ?, 'S5 overview', 'sh-1', ?)
                    """, projectId, tenantId, "s5-overview-" + projectId.toString().substring(0, 8));
            insertDevice(connection, UUID.randomUUID(), "online", "ONLINE", now.minus(Duration.ofDays(2)));
            insertDevice(connection, UUID.randomUUID(), "recent-offline", "OFFLINE", now.minus(Duration.ofHours(2)));
            insertDevice(connection, inactiveDeviceId, "inactive", "INACTIVE", null);
            // 设备发生时间故意很旧；只要平台在窗口内收到，就应进入运营概要。
            insertMessage(connection, 10, now.minus(Duration.ofDays(10)), now.minus(Duration.ofHours(1)));
            insertMessage(connection, 100, now, now.minus(Duration.ofHours(25)));
        }
    }

    /** 在 Redis 命中期间修改 PostgreSQL，用于证明请求不会每次 COUNT 主库。 */
    private void mutateFacts(Instant now) throws Exception {
        try (Connection connection = ownerConnection()) {
            execute(connection, "UPDATE dev_device SET status = 'ONLINE', last_online_at = ? WHERE id = ?",
                    Timestamp.from(now), inactiveDeviceId);
            insertMessage(connection, 20, now, now.minus(Duration.ofMinutes(5)));
        }
    }

    /** 写入一台项目设备。 */
    private void insertDevice(Connection connection, UUID id, String key, String status, Instant lastOnlineAt)
            throws Exception {
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_key, name, status, last_online_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, projectId, key, key, status,
                lastOnlineAt == null ? null : Timestamp.from(lastOnlineAt));
    }

    /** 写入一条可由 TimescaleDB 真实聚合的消息日志。 */
    private void insertMessage(Connection connection, int bytes, Instant occurredAt, Instant receivedAt)
            throws Exception {
        execute(connection, """
                INSERT INTO ts_device_message_log
                    (id, project_id, device_id, message_id, tenant_id, protocol, direction, raw_bytes, ts, received_at)
                VALUES (?, ?, ?, ?, ?, 'MQTT', 'UP', ?, ?, ?)
                """, UUID.randomUUID(), projectId, inactiveDeviceId, UUID.randomUUID(), tenantId,
                bytes, Timestamp.from(occurredAt), Timestamp.from(receivedAt));
    }

    /** @return 迁移所有者连接，仅用于建立与清理集成测试夹具 */
    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 使用参数绑定执行夹具 SQL，避免测试也引入字符串拼接示例。 */
    private static void execute(Connection connection, String sql, Object... arguments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }

    /** @return 包含项目 hash tag 与协议版本的生产缓存键 */
    private String redisKey() {
        return "things-link:overview:{" + projectId + "}:v3";
    }

}
