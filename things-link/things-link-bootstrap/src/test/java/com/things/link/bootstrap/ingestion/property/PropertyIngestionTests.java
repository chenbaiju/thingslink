package com.things.link.bootstrap.ingestion.property;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.PropertyAggregateBackfillMetrics;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import io.micrometer.core.instrument.MeterRegistry;
import com.things.link.telemetry.application.PropertyReportMessage;
import com.things.link.telemetry.application.DeviceMessageLogCommand;
import com.things.link.telemetry.application.MessageLogService;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@DisplayName("属性摄入接口（S3-6a）")
class PropertyIngestionTests extends AbstractIntegrationTest {
    /** 本夹具只验原事务；旧AFTER_COMMIT实时网络由独立真实Kafka用例验证，禁止误连开发Broker。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean(enforceOverride=true)
    private com.things.link.ingestion.application.RealtimeKafkaPublisher isolatedLegacyRealtimePublisher;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** 版本化数据面夹具用 1.0.0 快照：NUMBER 温度 + SWITCH 在线两个上报属性。 */
    private static final String PROPERTIES_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125},"
                    + "\"online\":{\"dataType\":\"SWITCH\",\"accessType\":\"REPORT\"}},"
                    + "\"events\":{},\"commands\":{}}";
    /** 2.0.0 验收快照额外加入原子 OBJECT，验证 JSONB 与聚合拒绝合同。 */
    private static final String COMPOSITE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125},"
                    + "\"online\":{\"dataType\":\"SWITCH\",\"accessType\":\"REPORT\"},"
                    + "\"labels\":{\"dataType\":\"OBJECT\",\"accessType\":\"REPORT\","
                    + "\"schema\":{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"maxProperties\":1,\"required\":[\"site\"],"
                    + "\"properties\":{\"site\":{\"type\":\"string\",\"maxLength\":32}}}}},"
                    + "\"events\":{},\"commands\":{}}";
    /** 压缩与回补并发时，被刷新 chunk 的瞬时 compression policy failure 重试上限（生产调度器下一轮自动重试）。 */
    private static final int MAX_COMPRESSION_RETRIES = 5;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthRateLimiter rateLimiter;
    /** 被测摄入应用端口。 */ @Autowired private PropertyIngestionService ingestionService;
    /** 被测消息日志应用服务。 */ @Autowired private MessageLogService messageLogService;
    /** 验证影子事务提交后才增加端到端延迟样本。 */ @Autowired private MeterRegistry meterRegistry;
    /** D-042 专项测试显式驱动扫描，测试配置把自动首次扫描推迟一小时。 */
    @Autowired private PropertyAggregateBackfillScanner aggregateBackfillScanner;

    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired private com.things.link.project.application.ProjectDailyQuotaDecisionService dailyQuotaDecision;

    private UUID projectId, deviceId, typeId;
    /** 测试租户。 */ private UUID tenantId;
    /** 测试账号。 */ private UUID accountId;
    private String token;

    @BeforeEach void seed() throws Exception {
        rateLimiter.clear();
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        String email = "telemetry-test@example.com";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        token = JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
        accountId = jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?", UUID.class, accountId);
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(v -> v.startsWith("tc_refresh=")).map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                .findFirst().orElseThrow();

        MvcResult proj = mockMvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"遥测项目\",\"region\":\"sh-1\"}")).andReturn();
        projectId = UUID.fromString(JSON.readTree(proj.getResponse().getContentAsString()).get("id").asString());
        MvcResult sw = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).cookie(new Cookie("tc_refresh", refresh))
                .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        token = JSON.readTree(sw.getResponse().getContentAsString()).get("accessToken").asString();

        // 创建设备类型（含温度属性）
        MvcResult type = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeKey\":\"thermometer\",\"name\":\"温度计\",\"deviceKind\":\"DIRECT\",\"payloadProtocol\":\"STANDARD\",\"networkType\":\"WIFI\"}")).andReturn();
        typeId = UUID.fromString(JSON.readTree(type.getResponse().getContentAsString()).get("id").asString());
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/properties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyKey\":\"temperature\",\"name\":\"温度\",\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\",\"unit\":\"℃\",\"minimumValue\":-40,\"maximumValue\":125,\"sortOrder\":0}")).andReturn();
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/properties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyKey\":\"online\",\"name\":\"在线标志\",\"accessType\":\"REPORT\",\"dataType\":\"SWITCH\",\"sortOrder\":1}"))
                .andReturn();

        // 发布必须在同一事务生成 1.0.0；这是模拟器 GitHub E2E 能进入版本化数据面的真实前置。
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/publish")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();

        // 创建设备并绑定类型
        MvcResult dev = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceKey\":\"sensor01\",\"name\":\"传感器1号\",\"deviceTypeId\":\"%s\"}".formatted(typeId))).andReturn();
        deviceId = UUID.fromString(JSON.readTree(dev.getResponse().getContentAsString()).get("id").asString());
    }

    /** 清理直接调用应用端口时设置的项目上下文。 */
    @AfterEach
    void clearTenantContext() {
        try {
            if (projectId != null && tenantId != null) {
                useProjectScope();
                jdbcTemplate.update("""
                        DELETE FROM sys_outbox_event WHERE project_id=?
                         AND event_type='AUTOMATION_PROPERTY_ACCEPTED'
                        """, projectId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** ADR0087：共享夹具重置必须先清版本化原始点，不能继续依赖项目级联抹掉摄入证据。 */
    @Test
    void fixtureResetExplicitlyClearsVersionedPointsBeforeProjectParents() throws Exception {
        useProjectScope();
        UUID previousProject=projectId;
        assertThat(ingestionService.ingest(standardMessage(Uuid7.generate(),Instant.now(),Instant.now(),Map.of("temperature",26.5)))).isTrue();
        assertThat(ownerCount("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id='"+previousProject+"' AND thing_model_version_id IS NOT NULL")).isEqualTo(1);
        TenantContext.clear();
        seed();
        assertThat(ownerCount("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id='"+previousProject+"'")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM public.sys_project WHERE id='"+previousProject+"'")).isZero();
        assertThat(projectId).isNotEqualTo(previousProject);
    }

    @Test void reportsPropertyAndStoresPoint() throws Exception {
        useProjectScope();
        long samplesBefore = meterRegistry.get("thingslink.ingestion.uplink.end_to_end").timer().count();
        boolean ingested = ingestionService.ingest(new PropertyReportMessage(
                Uuid7.generate(), projectId, deviceId, "temperature", 26.5, Instant.parse("2026-08-04T10:00:00Z")));
        assertThat(ingested).isTrue();

        // 验证时序点落库
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE device_id = ?", Integer.class, deviceId);
        assertThat(count).isEqualTo(1);
        assertThat(meterRegistry.get("thingslink.ingestion.uplink.end_to_end").timer().count())
                .isEqualTo(samplesBefore + 1);
    }

    /** 一条批量标准消息只抢占一次 inbox，并在同一事务写入全部属性、影子和一条消息日志。 */
    @Test
    void ingestsStandardBatchAtomicallyWithSingleInbox() {
        UUID messageId = Uuid7.generate();
        Instant occurredAt = Instant.parse("2026-08-05T10:00:02Z");
        Instant receivedAt = Instant.parse("2026-08-05T10:00:01Z");
        StandardUplinkMessage message = standardMessage(messageId, occurredAt, receivedAt,
                Map.of("temperature", 26.5, "online", true));

        // Kafka 线程没有 TenantContext；应用端口必须从可信标准信封建立事务级 RLS 范围。
        assertThat(ingestionService.ingest(message)).isTrue();
        assertThat(ingestionService.ingest(message)).isFalse();

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE message_id = ?", Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE message_id = ?", Integer.class, messageId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?", Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_bytes FROM ts_device_message_log WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(128);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id = ?", String.class, deviceId))
                .isEqualTo("26.5");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported ->> 'online' FROM dev_shadow WHERE device_id = ?", String.class, deviceId))
                .isEqualTo("true");
    }

    /** 存量标量省略 modelVersion 在兼容窗口内解析到已绑定初始版本，并打点 legacy_inferred（X-01 §5.2）。 */
    @Test
    void legacyScalarOmissionIsInferredAsInitialVersionAndRecorded() {
        double before = meterRegistry.get("thingslink.ingestion.model_version.legacy_inferred").counter().count();
        UUID messageId = Uuid7.generate();
        StandardUplinkMessage message = standardMessage(
                messageId, Instant.parse("2026-08-05T10:00:02Z"), Instant.parse("2026-08-05T10:00:01Z"),
                Map.of("temperature", 26.5), null);

        assertThat(ingestionService.ingest(message)).isTrue();

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id = ?", String.class, deviceId))
                .isEqualTo("26.5");
        assertThat(meterRegistry.get("thingslink.ingestion.model_version.legacy_inferred").counter().count())
                .as("省略推断应递增 legacy_inferred 指标").isEqualTo(before + 1);
    }

    /** 已经历升级的设备省略 modelVersion 必须拒绝，不能猜测其想写哪个版本。 */
    @Test
    void omissionAfterNonInitialTransitionIsRejected() {
        seedNonInitialTransition(tenantId, projectId, typeId, deviceId, PROPERTIES_SNAPSHOT);
        StandardUplinkMessage message = standardMessage(
                Uuid7.generate(), Instant.parse("2026-08-05T10:00:02Z"), Instant.parse("2026-08-05T10:00:01Z"),
                Map.of("temperature", 26.5), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingest(message))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class)
                .extracting("errorCode.code").isEqualTo(30054);
    }

    /** 发布类型与随后创建设备必须形成 1.0.0 + INITIAL 绑定，防止模拟器上报被版本 admission 丢弃。 */
    @Test
    void publishedTypeBindsNewDeviceToInitialVersion() {
        useProjectScope();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM dev_thing_model_version
                 WHERE project_id = ? AND device_type_id = ? AND version_number = '1.0.0'
                """, Integer.class, projectId, typeId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM dev_device d
                  JOIN dev_thing_model_version v ON v.id = d.thing_model_version_id
                 WHERE d.project_id = ? AND d.id = ? AND v.version_number = '1.0.0'
                """, Integer.class, projectId, deviceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM dev_device_model_binding_history
                 WHERE project_id = ? AND device_id = ? AND transition_type = 'INITIAL'
                """, Integer.class, projectId, deviceId)).isEqualTo(1);
    }

    /** 旧版十分钟窗口只保留历史与消息日志，不能毒化影子或当前行为链。 */
    @Test
    void historyOnlyStoresFactsWithoutUpdatingShadow() {
        seedNonInitialTransition(tenantId, projectId, typeId, deviceId, PROPERTIES_SNAPSHOT);
        UUID messageId = Uuid7.generate();
        Instant receivedAt = Instant.now().plusSeconds(1);

        assertThat(ingestionService.ingest(standardMessage(messageId,
                Instant.parse("2026-08-05T10:00:00Z"), receivedAt,
                Map.of("temperature", 18.5), "1.0.0"))).isTrue();

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ts_property_point
                 WHERE message_id = ? AND model_version = '1.0.0'
                """, Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM dev_shadow
                 WHERE project_id = ? AND device_id = ? AND reported -> 'temperature' IS NOT NULL
                """, Integer.class, projectId, deviceId)).isZero();
        assertThat(automationEventCount(messageId)).isZero();
    }

    /** 相同 messageId 只有版本和规范载荷都相同才是幂等重放，换载荷必须稳定冲突。 */
    @Test
    void rejectsReplayWithChangedPayload() {
        UUID messageId = Uuid7.generate();
        StandardUplinkMessage first = standardMessage(messageId,
                Instant.parse("2026-08-05T10:00:00Z"), Instant.parse("2026-08-05T10:00:01Z"),
                Map.of("temperature", 20.0));
        StandardUplinkMessage changed = standardMessage(messageId,
                first.occurredAt(), first.receivedAt(), Map.of("temperature", 21.0));

        assertThat(ingestionService.ingest(first)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingest(changed))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class)
                .extracting("errorCode.code").isEqualTo(30059);
    }

    /** 同一属性跨 1.0.0/2.0.0 的同分钟数据必须生成两个聚合分段，不能混算平均值。 */
    @Test
    void continuousAggregateSegmentsByModelVersion() {
        Instant start = Instant.parse("2026-08-05T11:00:00Z");
        ingestionService.ingest(standardMessage(Uuid7.generate(), start, start.plusSeconds(1),
                Map.of("temperature", 20.0), "1.0.0"));
        seedNonInitialTransition(tenantId, projectId, typeId, deviceId, PROPERTIES_SNAPSHOT);
        ingestionService.ingest(standardMessage(Uuid7.generate(), start.plusSeconds(30), start.plusSeconds(31),
                Map.of("temperature", 30.0), "2.0.0"));
        refreshOneMinuteWindowAsOwner(start, start.plusSeconds(120));

        useProjectScope();
        assertThat(jdbcTemplate.queryForList("""
                SELECT model_version FROM ts_property_point_1m
                 WHERE project_id = ? AND device_id = ? AND property_key = 'temperature'
                   AND bucket = ?
                 ORDER BY model_version
                """, String.class, projectId, deviceId, java.sql.Timestamp.from(start)))
                .containsExactly("1.0.0", "2.0.0");
    }

    /** OBJECT 以原生 JSONB 往返；数值聚合端点必须用稳定 30058 拒绝复合值。 */
    @Test
    void compositeValueRoundTripsAsJsonAndRejectsNumericAggregation() throws Exception {
        seedNonInitialTransition(tenantId, projectId, typeId, deviceId, COMPOSITE_SNAPSHOT);
        Instant occurredAt = recentHistoryTime("2026-08-05T12:00:00Z");
        UUID messageId = Uuid7.generate();
        ingestionService.ingest(standardMessage(messageId, occurredAt, occurredAt.plusSeconds(1),
                Map.of("labels", Map.of("site", "华东一厂")), "2.0.0"));

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT value_json ->> 'site' FROM ts_property_point
                 WHERE message_id = ? AND data_type = 'OBJECT' AND model_version = '2.0.0'
                """, String.class, messageId)).isEqualTo("华东一厂");
        TenantContext.clear();
        String path = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/telemetry/property";
        MvcResult raw = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("propertyKey", "labels")).andReturn();
        JsonNode rawBody = JSON.readTree(raw.getResponse().getContentAsString());
        assertThat(rawBody.get("items").get(0).get("value").get("site").asString()).isEqualTo("华东一厂");

        MvcResult aggregate = mockMvc.perform(get(path + "/history")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .queryParam("propertyKey", "labels")
                .queryParam("from", recentHistoryTime("2026-08-05T11:59:00Z").toString())
                .queryParam("to", recentHistoryTime("2026-08-05T12:01:00Z").toString())).andReturn();
        assertThat(aggregate.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(aggregate.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(30058);
    }

    /** 严重超额停止新增历史点，但同一事务仍保留 inbox、影子、消息日志与后续告警输入。 */
    @Test
    void degradedDailyQuotaKeepsCurrentFactsButSkipsHistoryPoints() {
        useProjectScope();
        jdbcTemplate.update("""
                INSERT INTO sys_usage_counter_daily
                    (id,tenant_id,project_id,usage_date,metric,used_value)
                VALUES (?,?,?,?, 'UPLINK_MESSAGE', ?)
                """,
                Uuid7.generate(), tenantId, projectId,
                java.time.LocalDate.now(java.time.ZoneOffset.UTC), 1_000_000_000L);
        TenantContext.clear();
        UUID messageId = Uuid7.generate();
        StandardUplinkMessage message = standardMessage(
                messageId, Instant.now(), Instant.now(),
                Map.of("temperature", 28.5, "online", true));

        assertThat(ingestionService.ingest(message)).isTrue();

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE message_id=?",
                Integer.class, messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE message_id=?",
                Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id=?",
                Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id=?",
                String.class, deviceId)).isEqualTo("28.5");
        assertThat(automationEventCount(messageId)).isEqualTo(1);
        jdbcTemplate.update(
                "DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric='UPLINK_MESSAGE'",
                projectId);
    }

    /** 零额度只关闭历史副作用，不能和正额度硬限混淆，更不能吞掉上行与告警事实。 */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}: limit={1}, used={2}, history={3}")
    @org.junit.jupiter.params.provider.CsvSource({
            "UPLINK_MESSAGE, 0, 0, 0",
            "UPLINK_MESSAGE, 10, 10, 2",
            "UPLINK_MESSAGE, 10, 12, 0",
            "UPLINK_BYTES, 0, 0, 0",
            "UPLINK_BYTES, 10, 10, 2",
            "UPLINK_BYTES, 10, 12, 0",
            "TIME_SERIES_POINT, 0, 0, 0",
            "TIME_SERIES_POINT, 10, 10, 2",
            "TIME_SERIES_POINT, 10, 12, 0"
    })
    void zeroDailyQuotaAndPositiveThresholdsPreserveCoreFacts(
            String metric, long limit, long used, int expectedHistoryPoints) throws Exception {
        UUID policyId = Uuid7.generate();
        UUID ruleId = Uuid7.generate();
        try (var owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var sql = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(owner, true));
            UUID previousPolicy = sql.queryForObject(
                    "SELECT quota_policy_id FROM sys_tenant WHERE id=?", UUID.class, tenantId);
            // 仅本次租户绑定独立策略，绝不修改共享套餐模板。其他两个指标明确不限。
            sql.update("""
                    INSERT INTO sys_quota_policy(id,code,uplink_message_daily_limit,
                        uplink_bytes_daily_limit,time_series_point_daily_limit)
                    VALUES (?,?,?,?,?)
                    """, policyId, "tq" + policyId.toString().replace("-", "").substring(0, 20),
                    "UPLINK_MESSAGE".equals(metric) ? limit : null,
                    "UPLINK_BYTES".equals(metric) ? limit : null,
                    "TIME_SERIES_POINT".equals(metric) ? limit : null);
            try {
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", policyId, tenantId);
                if (used > 0) {
                    sql.update("""
                            INSERT INTO sys_usage_counter_daily
                                (id,tenant_id,project_id,usage_date,metric,used_value)
                            VALUES (?,?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,?,?)
                            """, Uuid7.generate(), tenantId, projectId, metric, used);
                }
                sql.update("""
                        INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,
                            property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                        VALUES (?,?,?,'daily-quota','DAILY_QUOTA',?,'temperature','GT',30,'LT',25,'WARNING')
                        """, ruleId, tenantId, projectId, deviceId);
                for (String checkedMetric : java.util.List.of(
                        "UPLINK_MESSAGE", "UPLINK_BYTES", "TIME_SERIES_POINT")) {
                    boolean selected = checkedMetric.equals(metric);
                    var fact = sql.queryForMap("""
                            SELECT limit_value,tenant_used_value FROM trusted_project_daily_quota_decision(
                                ?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,?)
                            """, tenantId, projectId, checkedMetric);
                    assertThat(fact.get("limit_value")).as("%s effective limit", checkedMetric)
                            .isEqualTo(selected ? Long.valueOf(limit) : null);
                    assertThat(((Number) fact.get("tenant_used_value")).longValue())
                            .as("%s actual owner-tenant usage", checkedMetric).isEqualTo(selected ? used : 0L);
                    var decision = dailyQuotaDecision.decisionTrustedProject(tenantId, projectId,
                            com.things.link.project.application.QuotaMetric.valueOf(checkedMetric));
                    assertThat(decision.disabled()).as("%s disabled", checkedMetric)
                            .isEqualTo(selected && limit == 0);
                    assertThat(decision.status()).as("%s status", checkedMetric).isEqualTo(!selected
                            ? com.things.link.project.application.QuotaStatus.NORMAL
                            : used > limit ? com.things.link.project.application.QuotaStatus.DEGRADED
                            : com.things.link.project.application.QuotaStatus.HARD_LIMIT);
                }
                UUID messageId = Uuid7.generate();
                Instant now = Instant.now();
                StandardUplinkMessage message = standardMessage(messageId, now, now,
                        Map.of("temperature", 31.0, "online", true));
                TenantContext.clear();
                assertThat(ingestionService.ingest(message)).isTrue();
                assertThat(ingestionService.ingest(message)).as("同一上行只受理一次").isFalse();
                // 先验证保留事实，确保RED只由历史策略边界失败而不是误删核心消息造成。
                assertThat(sql.queryForObject("SELECT count(*) FROM sys_inbox_message WHERE message_id=?",
                        Integer.class, messageId)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT count(*) FROM ts_device_message_log WHERE message_id=?",
                        Integer.class, messageId)).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id=?",
                        String.class, deviceId)).isIn("31.0", "31");
                assertThat(sql.queryForList("SELECT condition_state FROM alarm_instance WHERE rule_id=?",
                        String.class, ruleId)).containsExactly("ACTIVE");
                assertThat(sql.queryForList("SELECT event_type FROM alarm_event WHERE source_message_id=?",
                        String.class, messageId)).containsExactlyInAnyOrder("PENDING", "ACTIVATED");
                assertThat(sql.queryForObject("""
                        SELECT count(*) FROM sys_outbox_event WHERE project_id=?
                         AND event_type='AUTOMATION_PROPERTY_ACCEPTED' AND payload::jsonb->>'sourceEventId'=?
                        """, Integer.class, projectId, messageId.toString())).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT count(*) FROM ts_property_point_internal WHERE message_id=?",
                        Integer.class, messageId)).as("%s limit=%s used=%s 历史副作用", metric, limit, used)
                        .isEqualTo(expectedHistoryPoints);
            } finally {
                sql.update("DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric=?", projectId, metric);
                sql.update("DELETE FROM alarm_instance WHERE rule_id=?", ruleId);
                sql.update("DELETE FROM alarm_rule WHERE id=?", ruleId);
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", previousPolicy, tenantId);
                sql.update("DELETE FROM sys_quota_policy WHERE id=?", policyId);
                TenantContext.clear();
            }
        }
    }

    /** 批量中任一属性不符合物模型时，整条消息不得占 inbox 或留下部分时序点。 */
    @Test
    void rejectsWholeStandardBatchBeforeAcquiringInbox() {
        UUID messageId = Uuid7.generate();
        StandardUplinkMessage message = standardMessage(
                messageId, Instant.parse("2026-08-05T10:00:00Z"), Instant.parse("2026-08-05T10:00:01Z"),
                Map.of("temperature", 26.5, "unknown_property", 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingest(message))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE message_id = ?", Integer.class, messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE message_id = ?", Integer.class, messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?", Integer.class, messageId)).isZero();
    }

    /** 外层事务回滚不能遗留inbox、业务点或自动化事件；重试沿原messageId成功。 */
    @Test
    void automationEventRollsBackWithPropertyTransactionAndRetryKeepsIdentity() {
        UUID messageId = Uuid7.generate();
        StandardUplinkMessage message = standardMessage(messageId, Instant.now(), Instant.now(),
                Map.of("temperature", 26.5));
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(ingestionService.ingest(message)).isTrue();
            assertThat(automationEventCount(messageId)).isEqualTo(1);
            status.setRollbackOnly();
        });
        useProjectScope();
        assertThat(automationEventCount(messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_inbox_message WHERE message_id=?",
                Integer.class, messageId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ts_property_point WHERE message_id=?",
                Integer.class, messageId)).isZero();
        assertThat(ingestionService.ingest(message)).isTrue();
        assertThat(ingestionService.ingest(message)).isFalse();
        assertThat(automationEventCount(messageId)).isEqualTo(1);
        var event = JSON.readTree(jdbcTemplate.queryForObject("""
                SELECT payload::text FROM sys_outbox_event
                 WHERE event_type='AUTOMATION_PROPERTY_ACCEPTED' AND payload::jsonb->>'sourceEventId'=?
                """, String.class, messageId.toString()));
        assertThat(event.get("modelVersion").asString()).isEqualTo("1.0.0");
        assertThat(event.get("payload").get("temperature").asDouble()).isEqualTo(26.5);
        assertThat(event.get("deviceId").asString()).isEqualTo(deviceId.toString());
        assertThat(Instant.parse(event.get("acceptedAt").asString())).isAfterOrEqualTo(message.receivedAt());
    }

    /** 仅查询当前项目事件，不能依赖共享Outbox全局为空。 */
    private int automationEventCount(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_outbox_event WHERE project_id=?
                 AND event_type='AUTOMATION_PROPERTY_ACCEPTED' AND payload::jsonb->>'sourceEventId'=?
                """, Integer.class, projectId, messageId.toString());
    }

    /** 创建 MQTT 属性上报标准信封，集中保持批量测试字段一致。 */
    private StandardUplinkMessage standardMessage(
            UUID messageId, Instant occurredAt, Instant receivedAt, Map<String, Object> payload) {
        return standardMessage(messageId, occurredAt, receivedAt, payload, "1.0.0");
    }

    /** @param modelVersion 设备声明语义版本；存量标量省略兼容窗口允许传 null。 */
    private StandardUplinkMessage standardMessage(
            UUID messageId, Instant occurredAt, Instant receivedAt, Map<String, Object> payload,
            String modelVersion) {
        return new StandardUplinkMessage(messageId, tenantId, projectId, deviceId, null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, modelVersion, occurredAt, receivedAt,
                "0123456789abcdef0123456789abcdef", 128, payload);
    }

    @Test void outOfRangeValueRejected() throws Exception {
        useProjectScope();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingest(new PropertyReportMessage(
                        Uuid7.generate(), projectId, deviceId, "temperature", 999,
                        Instant.parse("2026-08-04T10:00:00Z"))))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class)
                .extracting("errorCode.code").isEqualTo(10001);
    }

    /** 同一 messageId 的 Kafka 重试只产生一个时序点。 */
    @Test
    void duplicateMessageIsIdempotent() {
        useProjectScope();
        UUID messageId = Uuid7.generate();
        PropertyReportMessage message = new PropertyReportMessage(
                messageId, projectId, deviceId, "temperature", 26.5, Instant.parse("2026-08-04T10:00:00Z"));

        assertThat(ingestionService.ingest(message)).isTrue();
        assertThat(ingestionService.ingest(message)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE message_id = ?", Integer.class, messageId)).isEqualTo(1);
    }

    /** 晚到点保留历史，但不能让影子当前值回退。 */
    @Test
    void latePointDoesNotRollBackShadow() {
        // 第二条由平台更晚收到，但设备 occurredAt 回拨；历史保留两点，当前影子继续保持新设备时刻的值。
        ingestionService.ingest(standardMessage(Uuid7.generate(),
                Instant.parse("2026-08-04T10:01:00Z"), Instant.parse("2026-08-04T10:01:05Z"),
                Map.of("temperature", 30)));
        ingestionService.ingest(standardMessage(Uuid7.generate(),
                Instant.parse("2026-08-04T10:00:00Z"), Instant.parse("2026-08-04T10:02:00Z"),
                Map.of("temperature", 20)));

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id = ?", String.class, deviceId))
                .isEqualTo("30");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_property_point WHERE device_id = ?", Integer.class, deviceId)).isEqualTo(2);
    }

    /** 控制台 API 只读，并按统一 CursorPage 契约返回。 */
    @Test
    void historyUsesCursorPageAndWriteEndpointIsClosed() throws Exception {
        useProjectScope();
        ingestionService.ingest(new PropertyReportMessage(Uuid7.generate(), projectId, deviceId,
                "temperature", 26.5, recentHistoryTime("2026-08-04T10:00:00Z")));
        ingestionService.ingest(new PropertyReportMessage(Uuid7.generate(), projectId, deviceId,
                "temperature", 27.5, recentHistoryTime("2026-08-04T10:01:00Z")));
        TenantContext.clear();
        String path = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/telemetry/property";

        MvcResult getResult = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("propertyKey", "temperature").queryParam("limit", "1"))
                .andReturn();
        assertThat(getResult.getResponse().getStatus()).isEqualTo(200);
        var firstPage = JSON.readTree(getResult.getResponse().getContentAsString());
        assertThat(firstPage.get("items").size()).isEqualTo(1);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
        String cursor = firstPage.get("nextCursor").asString();

        MvcResult secondResult = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("propertyKey", "temperature").queryParam("limit", "1")
                        .queryParam("cursor", cursor))
                .andReturn();
        var secondPage = JSON.readTree(secondResult.getResponse().getContentAsString());
        assertThat(secondPage.get("items").size()).isEqualTo(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();

        MvcResult postResult = mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyKey\":\"temperature\",\"value\":99}"))
                .andReturn();
        assertThat(postResult.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    /** S4-1 聚合接口必须返回真实平均值，并在一分钟桶超过 2000 时自动提升到一小时。 */
    @Test
    void aggregateHistoryReturnsActualGranularityAndWeightedAverage() throws Exception {
        useProjectScope();
        ingestionService.ingest(new PropertyReportMessage(Uuid7.generate(), projectId, deviceId,
                "temperature", 10, recentHistoryTime("2026-08-04T10:00:10Z")));
        ingestionService.ingest(new PropertyReportMessage(Uuid7.generate(), projectId, deviceId,
                "temperature", 20, recentHistoryTime("2026-08-04T10:00:20Z")));
        ingestionService.ingest(new PropertyReportMessage(Uuid7.generate(), projectId, deviceId,
                "temperature", 30, recentHistoryTime("2026-08-04T10:01:10Z")));

        // WITH NO DATA 避免迁移扫描存量；测试显式回补，证明运维回补路径与查询使用同一份物化结果。
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CALL refresh_continuous_aggregate('ts_property_point_1m_internal', '" + recentHistoryTime("2026-08-04T10:00:00Z") + "', '" + recentHistoryTime("2026-08-04T10:03:00Z") + "')");
            statement.execute("CALL refresh_continuous_aggregate('ts_property_point_1h_internal', '" + recentHistoryTime("2026-08-04T10:00:00Z") + "', '" + recentHistoryTime("2026-08-04T11:00:00Z") + "')");
        }
        TenantContext.clear();

        String path = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/telemetry/property/history";
        MvcResult minuteResult = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("propertyKey", "temperature")
                        .queryParam("from", recentHistoryTime("2026-08-04T10:00:00Z").toString()).queryParam("to", recentHistoryTime("2026-08-04T10:03:00Z").toString())
                        .queryParam("granularity", "ONE_MINUTE").queryParam("aggregation", "AVG"))
                .andReturn();
        assertThat(minuteResult.getResponse().getStatus()).isEqualTo(200);
        var minute = JSON.readTree(minuteResult.getResponse().getContentAsString());
        assertThat(minute.get("requestedGranularity").asString()).isEqualTo("ONE_MINUTE");
        assertThat(minute.get("actualGranularity").asString()).isEqualTo("ONE_MINUTE");
        assertThat(minute.get("points").size()).isEqualTo(2);
        assertThat(minute.get("points").get(0).get("value").asDouble()).isEqualTo(15.0);
        assertThat(minute.get("points").get(0).get("sampleCount").asLong()).isEqualTo(2);

        MvcResult upgradedResult = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("propertyKey", "temperature")
                        .queryParam("from", recentHistoryTime("2026-08-01T00:00:00Z").toString()).queryParam("to", recentHistoryTime("2026-08-04T12:00:00Z").toString())
                        .queryParam("granularity", "ONE_MINUTE").queryParam("aggregation", "AVG"))
                .andReturn();
        var upgraded = JSON.readTree(upgradedResult.getResponse().getContentAsString());
        assertThat(upgraded.get("actualGranularity").asString()).isEqualTo("ONE_HOUR");
    }

    /** ADR 0015 的三层聚合、刷新、压缩与保留必须真实注册为 TimescaleDB 作业。 */
    @Test
    void propertyHistoryPoliciesMatchAdr0015() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT view_name FROM timescaledb_information.continuous_aggregates
                 WHERE view_schema = 'public' AND view_name LIKE 'ts_property_point_%'
                 ORDER BY view_name
                """, String.class)).containsExactly(
                "ts_property_point_1d_internal", "ts_property_point_1h_internal", "ts_property_point_1m_internal");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM timescaledb_information.jobs
                 WHERE proc_name IN ('policy_refresh_continuous_aggregate', 'policy_compression', 'policy_retention')
                   AND (hypertable_name = 'ts_property_point_internal' OR hypertable_name LIKE '_materialized_hypertable_%')
                """, Integer.class)).isGreaterThanOrEqualTo(7);
    }

    /** 超过七天的迟到数值点必须自动登记单日窗口，并在三层刷新与 raw 对账后删除请求。 */
    @Test
    void latePointBackfillConvergesAllAggregateLayers() {
        Instant windowStart = recentBackfillStart(10);
        Instant firstAt = windowStart.plusSeconds(36010);
        Instant lateAt = windowStart.plusSeconds(36020);
        ingestionService.ingest(standardMessage(
                Uuid7.generate(), firstAt, firstAt.plusSeconds(1), Map.of("temperature", 10)));
        refreshWindowAsOwner(windowStart, windowStart.plusSeconds(86400));

        double successesBefore = aggregateBackfillCounter("success");
        ingestionService.ingest(standardMessage(
                Uuid7.generate(), lateAt, Instant.now(), Map.of("temperature", 30)));

        assertThat(ownerCount("SELECT count(*) FROM ts_property_aggregate_backfill WHERE project_id = '"
                + projectId + "'")).isEqualTo(1);
        useProjectScope();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sample_count FROM ts_property_point_1m
                 WHERE project_id = ? AND device_id = ? AND property_key = 'temperature'
                   AND bucket = ?
                """, Long.class, projectId, deviceId, java.sql.Timestamp.from(windowStart.plusSeconds(36000)))).isEqualTo(1L);
        TenantContext.clear();

        aggregateBackfillScanner.scan();

        useProjectScope();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sample_count FROM ts_property_point_1m
                 WHERE project_id = ? AND device_id = ? AND property_key = 'temperature'
                   AND bucket = ?
                """, Long.class, projectId, deviceId, java.sql.Timestamp.from(windowStart.plusSeconds(36000)))).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum_value FROM ts_property_point_1m
                 WHERE project_id = ? AND device_id = ? AND property_key = 'temperature'
                   AND bucket = ?
                """, Double.class, projectId, deviceId, java.sql.Timestamp.from(windowStart.plusSeconds(36000)))).isEqualTo(40.0);
        TenantContext.clear();
        assertThat(ownerCount("SELECT property_aggregate_window_mismatch_count('" + projectId
                + "', '" + windowStart + "', '" + windowStart.plusSeconds(86400) + "')")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM ts_property_aggregate_backfill WHERE project_id = '"
                + projectId + "'")).isZero();
        assertThat(aggregateBackfillCounter("success")).isEqualTo(successesBefore + 1);
    }

    /** 回补与真实压缩、保留作业并发时必须收敛；旧于九十天的 raw chunk 同时应被真实删除。 */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void backfillConvergesWhileCompressionAndRetentionRunConcurrently() throws Exception {
        Instant windowStart = recentBackfillStart(20);
        Instant firstAt = windowStart.plusSeconds(36010);
        Instant lateAt = windowStart.plusSeconds(36020);
        UUID expiredMessageId = Uuid7.generate();
        ingestionService.ingest(standardMessage(
                Uuid7.generate(), firstAt, firstAt.plusSeconds(1), Map.of("temperature", 11)));
        refreshWindowAsOwner(windowStart, windowStart.plusSeconds(86400));
        ingestionService.ingest(standardMessage(
                Uuid7.generate(), lateAt, Instant.now(), Map.of("temperature", 31)));
        insertExpiredRawPointAsOwner(expiredMessageId);

        int compressionJob = ownerCount("""
                SELECT job_id FROM timescaledb_information.jobs
                 WHERE hypertable_name = 'ts_property_point_internal' AND proc_name = 'policy_compression'
                """);
        int retentionJob = ownerCount("""
                SELECT job_id FROM timescaledb_information.jobs
                 WHERE hypertable_name = 'ts_property_point_internal' AND proc_name = 'policy_retention'
                """);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> compression = executor.submit(() -> runCompressionJobAfter(start, compressionJob));
            Future<?> retention = executor.submit(() -> runTimescaleJobAfter(start, retentionJob));
            Future<?> backfill = executor.submit(() -> {
                await(start);
                aggregateBackfillScanner.scan();
            });
            start.countDown();
            compression.get(60, TimeUnit.SECONDS);
            retention.get(60, TimeUnit.SECONDS);
            backfill.get(60, TimeUnit.SECONDS);
        }

        assertThat(ownerCount("SELECT property_aggregate_window_mismatch_count('" + projectId
                + "', '" + windowStart + "', '" + windowStart.plusSeconds(86400) + "')")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM ts_property_aggregate_backfill WHERE project_id = '"
                + projectId + "'")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM ts_property_point_internal WHERE message_id = '"
                + expiredMessageId + "'")).isZero();
        assertThat(ownerCount("""
                SELECT count(*) FROM timescaledb_information.chunks
                 WHERE hypertable_name = 'ts_property_point_internal'
                   AND range_start <= '%s'
                   AND range_end > '%s'
                   AND is_compressed
                """.formatted(windowStart.plusSeconds(36000), windowStart.plusSeconds(36000)))).isEqualTo(1);
    }

    /** inbox 使用原生 RLS；原始时序因 Timescale 压缩限制使用 fail-closed 安全视图。 */
    @Test
    void ingestionTablesHaveProjectRls() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                 WHERE c.oid IN ('sys_inbox_message'::regclass)
                   AND c.relrowsecurity
                   AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                 ORDER BY c.relname
                """, String.class)).containsExactly("sys_inbox_message");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT definition FROM pg_views
                 WHERE schemaname = 'public' AND viewname = 'ts_property_point'
                """, String.class)).contains("app_current_project()");
    }

    /** messageId 并发安全幂等，且摘要会在入库前按 Unicode 码点截断。 */
    @Test
    void messageLogIsIdempotentAndBoundsPayload() {
        useProjectScope();
        UUID messageId = Uuid7.generate();
        String longSummary = "测".repeat(300);
        DeviceMessageLogCommand command = messageCommand(
                messageId, TransportProtocol.MQTT, DeviceMessageLog.Direction.UP,
                "trace-message-1", longSummary, Instant.parse("2026-08-04T11:00:00Z"));

        assertThat(messageLogService.log(command)).isTrue();
        assertThat(messageLogService.log(command)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT char_length(payload_summary) FROM ts_device_message_log WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(256);
    }

    /** 控制台查询支持筛选和真实 nextCursor，不使用 offset。 */
    @Test
    void messageLogApiFiltersAndPaginates() throws Exception {
        useProjectScope();
        messageLogService.log(messageCommand(Uuid7.generate(), TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-page", "first", Instant.parse("2026-08-04T11:00:00Z")));
        messageLogService.log(messageCommand(Uuid7.generate(), TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-page", "second", Instant.parse("2026-08-04T11:01:00Z")));
        messageLogService.log(messageCommand(Uuid7.generate(), TransportProtocol.HTTP,
                DeviceMessageLog.Direction.DOWN, "trace-other", "ignored", Instant.parse("2026-08-04T11:02:00Z")));
        TenantContext.clear();

        String path = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/messages";
        MvcResult first = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("protocol", "MQTT").queryParam("direction", "UP")
                        .queryParam("traceId", "trace-page").queryParam("limit", "1"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var firstPage = JSON.readTree(first.getResponse().getContentAsString());
        assertThat(firstPage.get("items").size()).isEqualTo(1);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();

        MvcResult second = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("protocol", "MQTT").queryParam("direction", "UP")
                        .queryParam("traceId", "trace-page").queryParam("limit", "1")
                        .queryParam("cursor", firstPage.get("nextCursor").asString()))
                .andReturn();
        var secondPage = JSON.readTree(second.getResponse().getContentAsString());
        assertThat(secondPage.get("items").size()).isEqualTo(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
    }

    /** 最终装配必须同时具备消息日志 RLS、幂等表 RLS 和 90 天保留任务。 */
    @Test
    void messageLogHasRlsAndRetentionPolicy() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                 WHERE c.oid IN ('ts_device_message_log'::regclass, 'sys_message_log_inbox'::regclass)
                   AND c.relrowsecurity
                   AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                 ORDER BY c.relname
                """, String.class)).containsExactly("sys_message_log_inbox", "ts_device_message_log");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM timescaledb_information.jobs
                 WHERE hypertable_schema = 'public' AND hypertable_name = 'ts_device_message_log'
                   AND proc_name = 'policy_retention'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM timescaledb_information.jobs
                 WHERE proc_schema = 'public' AND proc_name = 'purge_message_log_inbox'
                """, Integer.class)).isEqualTo(1);
    }

    /** inbox 清理作业必须只删除超过最大重试窗口的幂等记录，不能误删仍可能被 Kafka 重投的消息。 */
    @Test
    void inboxMessageRetentionJobPurgesOnlyExpiredRecords() {
        useProjectScope();
        UUID expiredMessageId = Uuid7.generate();
        UUID retainedMessageId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_inbox_message (message_id, project_id, received_at)
                VALUES (?, ?, now() - INTERVAL '91 days'), (?, ?, now() - INTERVAL '89 days')
                """, expiredMessageId, projectId, retainedMessageId, projectId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM timescaledb_information.jobs
                 WHERE proc_schema = 'public' AND proc_name = 'purge_inbox_message'
                """, Integer.class)).isEqualTo(1);

        // 生产应用角色无权手动执行 SECURITY DEFINER 清理函数；用迁移角色模拟 TimescaleDB 调度器。
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SELECT purge_inbox_message(NULL, '{}'::jsonb)");
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("无法以作业角色执行 inbox 清理函数", exception);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE message_id = ?", Integer.class, expiredMessageId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE message_id = ?", Integer.class, retainedMessageId))
                .isEqualTo(1);
    }

    /** 非法枚举和越界页大小必须返回稳定的 10001，而不是落入内部错误 90000。 */
    @Test
    void invalidMessageLogQueryReturnsParameterError() throws Exception {
        String path = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/messages";
        MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .queryParam("protocol", "BROKEN").queryParam("limit", "0"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(10001);
    }

    /** 构造使用可信测试设备归属的消息命令。 */
    private DeviceMessageLogCommand messageCommand(UUID messageId, TransportProtocol protocol,
                                                    DeviceMessageLog.Direction direction, String traceId,
                                                    String summary, Instant occurredAt) {
        return new DeviceMessageLogCommand(projectId, deviceId, messageId, protocol, direction,
                "tc/v1/project/sensor01/up/property/report", summary, summary.length(), null,
                occurredAt, occurredAt.plusMillis(20), traceId);
    }

    /** 为直接调用的 Kafka 消费应用端口设置项目 RLS 上下文。 */
    private void useProjectScope() {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
    }

    /** @param days 仍在raw安全保留期的年龄 @return 数据库当前UTC日界，防止回补夹具随日历过期 */
    private Instant recentBackfillStart(int days) {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = connection.prepareStatement("SELECT date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') "
                     + "AT TIME ZONE 'UTC' - ? * interval '1 day'")) {
            query.setInt(1, days);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getTimestamp(1).toInstant();
            }
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("无法取得回补测试数据库日界", failure);
        }
    }

    /** 以迁移 owner 直接执行三层指定窗口刷新；Timescale 禁止从自定义过程嵌套调用该原生过程。 */
    private static void refreshWindowAsOwner(Instant start, Instant end) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            for (String aggregate : java.util.List.of(
                    "ts_property_point_1m_internal",
                    "ts_property_point_1h_internal",
                    "ts_property_point_1d_internal")) {
                try (var statement = connection.prepareCall(
                        "CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)")) {
                    statement.setString(1, aggregate);
                    statement.setTimestamp(2, java.sql.Timestamp.from(start));
                    statement.setTimestamp(3, java.sql.Timestamp.from(end));
                    statement.execute();
                }
            }
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("无法执行属性聚合指定窗口刷新", exception);
        }
    }

    /** 仅刷新本切片断言使用的分钟聚合，避免用短窗口调用小时/天聚合触发最小桶宽错误。 */
    private static void refreshOneMinuteWindowAsOwner(Instant start, Instant end) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareCall(
                     "CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)")) {
            statement.setString(1, "ts_property_point_1m_internal");
            statement.setTimestamp(2, java.sql.Timestamp.from(start));
            statement.setTimestamp(3, java.sql.Timestamp.from(end));
            statement.execute();
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("无法执行属性分钟聚合指定窗口刷新", exception);
        }
    }

    /** 写入一个只供本用例验证九十天 raw 保留作业的独立旧 chunk。 */
    private void insertExpiredRawPointAsOwner(UUID messageId) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO ts_property_point_internal
                         (project_id, device_id, property_key, ts, message_id, value_double, quality)
                     VALUES (?, ?, 'temperature', now() - interval '120 days', ?, 99, 0)
                     """)) {
            statement.setObject(1, projectId);
            statement.setObject(2, deviceId);
            statement.setObject(3, messageId);
            statement.executeUpdate();
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("无法准备属性 raw 保留夹具", exception);
        }
    }

    /** 在线程屏障后以迁移 owner 真实执行一个 TimescaleDB policy job。 */
    private static void runTimescaleJobAfter(CountDownLatch start, int jobId) {
        await(start);
        try {
            runTimescaleJob(jobId);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("TimescaleDB policy job 并发执行失败", exception);
        }
    }

    /**
     * 压缩作业与回补刷新并发时，正在刷新的 chunk 会让压缩抛瞬时 {@code compression policy failure}；
     * 生产后台调度器下一轮会自动重试尚未压缩的 chunk，这里以有界重试建模同一语义，而非把瞬时冲突判成测试失败。
     */
    private static void runCompressionJobAfter(CountDownLatch start, int jobId) {
        await(start);
        java.sql.SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_COMPRESSION_RETRIES; attempt++) {
            try {
                runTimescaleJob(jobId);
                return;
            } catch (java.sql.SQLException exception) {
                lastFailure = exception;
                if (!isTransientCompressionFailure(exception)) {
                    throw new IllegalStateException("TimescaleDB 压缩作业执行失败", exception);
                }
                awaitRetry(attempt);
            }
        }
        throw new IllegalStateException("TimescaleDB 压缩作业在有界重试后仍未收敛", lastFailure);
    }

    /** 以迁移 owner 调用 {@code run_job}，SQL 异常上抛由调用方决定重试或失败。 */
    private static void runTimescaleJob(int jobId) throws java.sql.SQLException {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareCall("CALL run_job(?)")) {
            statement.setInt(1, jobId);
            statement.execute();
        }
    }

    /** 压缩作业与回补并发时的部分压缩失败可重试；其余 SQL 失败立即失败。 */
    private static boolean isTransientCompressionFailure(java.sql.SQLException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("compression policy failure")) {
                return true;
            }
        }
        return false;
    }

    /** 有界退避等待回补刷新释放目标 chunk 后重试压缩。 */
    private static void awaitRetry(int attempt) {
        try {
            TimeUnit.SECONDS.sleep(Math.min(attempt, 3L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待压缩重试时被中断", interrupted);
        }
    }

    /** 等待并发起跑屏障；中断恢复标志后失败当前测试。 */
    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发起跑屏障时被中断", exception);
        }
    }

    /** 以迁移 owner 执行只返回一个整数的数据库验收查询。 */
    private static int ownerCount(String sql) {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new AssertionError("数据库验收查询没有返回行");
            }
            return resultSet.getInt(1);
        } catch (java.sql.SQLException exception) {
            throw new AssertionError("数据库验收查询失败", exception);
        }
    }

    /** @return 指定固定结果的聚合回补计数器值 */
    private double aggregateBackfillCounter(String result) {
        return meterRegistry.get(PropertyAggregateBackfillMetrics.BACKFILLS)
                .tag("result", result).counter().count();
    }
    /** 保留旧测试的相对间距与UTC桶对齐，使用当前前两天而非会过期的固定日期。 */
    private static Instant recentHistoryTime(String original) {
        long offset = java.time.Duration.between(Instant.parse("2026-08-05T00:00:00Z"), Instant.parse(original)).getSeconds();
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusSeconds(2*86400).plusSeconds(offset);
    }

    /** 缺投影以相同50048拒绝曲线和原始分页，不能以空历史冒充成功。 */
    @Test void missingHistoryPlanFailsBothHttpEntrances() throws Exception {
        jdbcTemplate.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",tenantId);
        String path="/api/v1/projects/"+projectId+"/devices/"+deviceId+"/telemetry/property";
        for (String suffix : java.util.List.of("", "/history")) {
            var result=mockMvc.perform(get(path+suffix).header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
                .queryParam("propertyKey","temperature").queryParam("from",recentHistoryTime("2026-08-05T00:00:00Z").toString())
                .queryParam("to",Instant.now().toString())).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(50048);
        }
    }

}
