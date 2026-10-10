package com.things.link.bootstrap.enduser;

import com.things.link.iam.infrastructure.security.JwtProperties;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App 设备数据面接口的集成验收（S11-2b）。
 *
 * <p>钉住两层授权矩阵（{@code app_user_role} × {@code app_user_device}）与复用
 * device/telemetry 数据面端口的端到端行为：列表/详情/当前值/历史/命令都经真实登录签发的
 * App 令牌、真实 PostgreSQL RLS 与真实设备事实链走通。设备事实直接 SQL 种子（复用 S9 的
 * {@code dev_type + dev_device + dev_property_definition} 模式，补 {@code dev_command_definition}
 * 与 {@code dev_shadow}/{@code ts_property_point}），因为本片关注的是信任边界而非预置服务。
 */
@DisplayName("App 设备数据面（S11-2b）")
@AutoConfigureMockMvc
class AppDeviceApiTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 控制台令牌签发器 + 配置：用于验证 App 端点拒绝控制台令牌。 */
    @Autowired
    @Qualifier("jwtEncoder")
    private JwtEncoder consoleJwtEncoder;

    @Autowired
    private JwtProperties consoleJwtProperties;

    /** 本用例插入的租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    private final Set<UUID> statisticsAccounts = new LinkedHashSet<>();
    /** 项目 → 归属租户。 */
    private final Map<UUID, UUID> projectTenants = new LinkedHashMap<>();

    private UUID tenantId;
    private UUID projectId;
    private String projectKey;
    private UUID otherProjectId;

    private UUID typeId;

    private UUID devicePrimary;   // PRIMARY 绑定（控制放行）
    private UUID deviceMember;    // MEMBER 绑定
    private UUID deviceReadOnly;  // READ_ONLY 绑定（读放行、控制 60011）
    private UUID deviceObserver;  // OBSERVER 角色 + PRIMARY 绑定
    private UUID deviceUnbound;   // 本项目未绑定 → 60010
    private UUID deviceOtherProject; // 其他项目 → 60010

    private UUID userPrimary;   // APP_ADMIN + PRIMARY/MEMBER/READ_ONLY 三绑定
    private UUID userObserver;  // OBSERVER + PRIMARY

    @BeforeEach
    void setUp() {
        // 每用例独立夹具，避免共享容器跨用例污染
        tenantId = newTenant("S11-2b 租户");
        projectKey = uniqueProjectKey();
        projectId = newProject(tenantId, projectKey);
        otherProjectId = newProject(tenantId, uniqueProjectKey());

        typeId = seedDeviceType(tenantId, projectId, "type-main");
        devicePrimary = seedDevice(tenantId, projectId, typeId, "dev-primary");
        deviceMember = seedDevice(tenantId, projectId, typeId, "dev-member");
        deviceReadOnly = seedDevice(tenantId, projectId, typeId, "dev-readonly");
        deviceObserver = seedDevice(tenantId, projectId, typeId, "dev-observer");
        deviceUnbound = seedDevice(tenantId, projectId, typeId, "dev-unbound");

        UUID otherTypeId = seedDeviceType(tenantId, otherProjectId, "type-other");
        deviceOtherProject = seedDevice(tenantId, otherProjectId, otherTypeId, "dev-other");

        seedPropertyDefinition(tenantId, projectId, typeId);
        seedCommandDefinition(tenantId, projectId, typeId);
        seedShadow(tenantId, projectId, devicePrimary, 23.5);
        seedShadow(tenantId, projectId, deviceReadOnly, 21.0);
        seedPropertyPoints(tenantId, projectId, deviceReadOnly);

        userPrimary = newUser(tenantId, "alice");
        userObserver = newUser(tenantId, "bob");
        addRole(tenantId, projectId, userPrimary, "APP_ADMIN");
        addRole(tenantId, projectId, userObserver, "OBSERVER");
        addBinding(tenantId, projectId, userPrimary, devicePrimary, "PRIMARY");
        addBinding(tenantId, projectId, userPrimary, deviceMember, "MEMBER");
        addBinding(tenantId, projectId, userPrimary, deviceReadOnly, "READ_ONLY");
        addBinding(tenantId, projectId, userObserver, deviceObserver, "PRIMARY");
    }

    @AfterEach
    void cleanup() {
        try {
            // app_refresh_token 是上下文建立类 RLS 豁免表，无上下文直接删（且外键指向 app_user/sys_project）
            jdbcTemplate.update("DELETE FROM app_refresh_token");
            for (Map.Entry<UUID, UUID> entry : projectTenants.entrySet()) {
                UUID pid = entry.getKey();
                UUID tid = entry.getValue();
                TenantContext.set(new TenantScope(tid, pid, Uuid7.generate()));
                try {
                    jdbcTemplate.update("DELETE FROM ts_device_command_attempt WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM ts_device_command WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM sys_outbox_event WHERE project_id = ?", pid);
                    // ts_property_point 是 RLS 保护的 hypertable，TimescaleDB 对带
                    // current_setting 策略的 DELETE 会报 "variable not found in subplan
                    // target list"（与 S7/S8/S9 一致，均不回删时序点）。该表无外键指向
                    // sys_project，遗留行随本用例唯一 projectId 成为孤儿，不影响后续用例。
                    jdbcTemplate.update("DELETE FROM alarm_instance WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM alarm_rule WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM dev_shadow WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM dev_device WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM dev_command_definition WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM dev_property_definition WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM dev_type WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM app_user_device WHERE project_id = ?", pid);
                    jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", pid);
                } finally {
                    TenantContext.clear();
                }
            }
            for (UUID tid : tenantIds) {
                TenantContext.set(new TenantScope(tid, null, Uuid7.generate()));
                try {
                    jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", tid);
                } finally {
                    TenantContext.clear();
                }
            }
            TenantContext.clear();
            for (UUID pid : projectTenants.keySet()) {
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", pid);
            }
            for (UUID account : statisticsAccounts) jdbcTemplate.update("DELETE FROM sys_account WHERE id=?", account);
            for (UUID tid : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tid);
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void statisticsUseAuthorizedDevicesAndDistinctActiveAlarms() throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("UPDATE dev_device SET status='OFFLINE' WHERE project_id=?", projectId);
            jdbcTemplate.update("UPDATE dev_device SET status='ONLINE',last_data_report_at=now()-interval '1 hour' WHERE id IN (?,?)",
                    devicePrimary, deviceUnbound);
            jdbcTemplate.update("UPDATE dev_device SET last_data_report_at=now()-interval '25 hours' WHERE id=?", deviceMember);
            statisticsAlarm(devicePrimary, "ACTIVE");
            statisticsAlarm(devicePrimary, "ACTIVE");
            statisticsAlarm(deviceMember, "PENDING");
            statisticsAlarm(deviceUnbound, "ACTIVE");
        } finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.online").value(1)).andExpect(jsonPath("$.active24h").value(1))
                .andExpect(jsonPath("$.alarming").value(1)).andExpect(jsonPath("$.asOf").isString());
    }

    @Test
    void statisticsExcludeClosedAndDeletedDevicesAndRejectLostRole() throws Exception {
        String token = login("alice");
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=? AND device_id=?",
                    userPrimary, deviceMember);
            jdbcTemplate.update("UPDATE dev_device SET deleted_at=now() WHERE id=?", deviceReadOnly);
        } finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", userPrimary); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + consoleToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyStatisticsHaveRealZeroAndProjectionRemainsRlsReadOnly() throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=?", userObserver); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.online").value(0)).andExpect(jsonPath("$.active24h").value(0))
                .andExpect(jsonPath("$.alarming").value(0));
        for (String view : java.util.List.of("dev_device_app_v1", "alarm_app_active_device_v1", "alarm_app_history_v1")) {
            assertThat(jdbcTemplate.queryForObject("SELECT 'security_invoker=true'=ANY(reloptions) FROM pg_class WHERE oid=?::regclass",
                    Boolean.class, view)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT has_table_privilege('thingslink_app',?,'UPDATE')", Boolean.class, view)).isFalse();
        }
        TenantContext.set(new TenantScope(tenantId, otherProjectId, Uuid7.generate()));
        try {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_device_app_v1 WHERE project_id=?", Long.class, projectId)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM alarm_app_history_v1 WHERE project_id=?", Long.class, projectId)).isZero();
        } finally { TenantContext.clear(); }
    }

    @Test
    void statisticsDistinguishActivityWindowAndAcknowledgedAlarm() throws Exception {
        UUID actor = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','统计确认账号')",
                actor, actor + "@example.test");
        statisticsAccounts.add(actor);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("UPDATE dev_device SET last_data_report_at=now()-interval '24 hours' WHERE id=?", devicePrimary);
            jdbcTemplate.update("UPDATE dev_device SET last_data_report_at=now()-interval '23 hours 59 minutes' WHERE id=?", deviceMember);
            jdbcTemplate.update("UPDATE dev_device SET last_data_report_at=now()+interval '1 day' WHERE id=?", deviceReadOnly);
            statisticsAlarm(devicePrimary, "ACTIVE");
            jdbcTemplate.update("UPDATE alarm_instance SET ack_state='ACKNOWLEDGED',acknowledged_at=now(),acknowledged_by=? WHERE project_id=?",
                    actor, projectId);
        } finally { TenantContext.clear(); }
        String token = login("alice");
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active24h").value(1))
                .andExpect(jsonPath("$.alarming").value(1));
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE alarm_instance SET condition_state='CLEARED',cleared_at=now(),clear_reason='AUTO_RECOVERY' WHERE project_id=?", projectId); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/statistics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alarming").value(0));
    }

    private UUID statisticsAlarm(UUID device, String condition) {
        UUID rule = Uuid7.generate();
        UUID alarm = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                    trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,?,'TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')
                """, rule, tenantId, projectId, "App统计规则-" + rule, device);
        jdbcTemplate.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                    alarm_type,severity,condition_state,first_condition_at,activated_at,last_received_at,last_value)
                VALUES (?,?,?,?,'DEVICE',?,'TEMPERATURE','MAJOR',?,now(),
                    CASE WHEN ?='ACTIVE' THEN now() ELSE NULL END,now(),31)
                """, alarm, tenantId, projectId, rule, device, condition, condition);
        return alarm;
    }

    @Test
    void alarmHistoryFiltersBeforePagingAndBindsSignedCursor() throws Exception {
        UUID first, second;
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            first = statisticsAlarm(devicePrimary, "ACTIVE");
            second = statisticsAlarm(deviceReadOnly, "ACTIVE");
            statisticsAlarm(deviceUnbound, "ACTIVE");
            statisticsAlarm(deviceMember, "PENDING");
            UUID recovered = statisticsAlarm(deviceMember, "PENDING");
            jdbcTemplate.update("UPDATE alarm_instance SET condition_state='CLEARED',cleared_at=now(),clear_reason='AUTO_RECOVERY' WHERE id=?", recovered);
            jdbcTemplate.update("UPDATE alarm_instance SET first_condition_at='2026-10-02T00:00:00Z',activated_at='2026-10-02T00:01:00Z' WHERE id=?", first);
            jdbcTemplate.update("UPDATE alarm_instance SET first_condition_at='2026-10-01T00:00:00Z',activated_at='2026-10-01T00:01:00Z',condition_state='CLEARED',cleared_at=now(),clear_reason='AUTO_RECOVERY' WHERE id=?", second);
        } finally { TenantContext.clear(); }
        String token = login("alice");
        var response = mockMvc.perform(get("/api/v1/app/alarms").param("limit","1").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        String cursor = objectMapper.readTree(response.getResponse().getContentAsString()).get("nextCursor").asText();
        mockMvc.perform(get("/api/v1/app/alarms").param("limit","1").param("cursor",cursor).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasMore").value(false));
        for (String bad : new String[]{cursor+"x","bad"})
            mockMvc.perform(get("/api/v1/app/alarms").param("limit","1").param("cursor",bad).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/app/alarms").param("limit","1").param("cursor",cursor).param("severity","INFO").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/app/alarms").param("limit","1").param("cursor",cursor).header("Authorization","Bearer "+login("bob"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/app/alarms").param("deviceId",deviceReadOnly.toString()).param("conditionState","CLEARED").param("severity","MAJOR")
                        .param("from","2026-10-01T00:00:00Z").param("to","2026-10-02T00:00:00Z").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(second.toString()));
        mockMvc.perform(get("/api/v1/app/alarms").param("from","2026-10-02T00:00:00Z").param("to","2026-10-03T00:00:00Z").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/app/alarms").param("deviceId",deviceOtherProject.toString()).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void alarmDetailRechecksBindingsAndDoesNotLeakPrivateFields() throws Exception {
        UUID visible, pending, unbound;
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            visible=statisticsAlarm(deviceReadOnly,"ACTIVE");
            pending=statisticsAlarm(devicePrimary,"PENDING");
            unbound=statisticsAlarm(deviceUnbound,"ACTIVE");
        } finally { TenantContext.clear(); }
        String token=login("alice");
        mockMvc.perform(get("/api/v1/app/alarms/"+visible).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").value(deviceReadOnly.toString()))
                .andExpect(jsonPath("$.conditionState").value("ACTIVE")).andExpect(jsonPath("$.ackState").value("UNACKNOWLEDGED"))
                .andExpect(jsonPath("$.ruleId").doesNotExist()).andExpect(jsonPath("$.lastValue").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist()).andExpect(jsonPath("$.acknowledgedBy").doesNotExist());
        for (UUID id : new UUID[]{pending,unbound,Uuid7.generate()})
            mockMvc.perform(get("/api/v1/app/alarms/"+id).header("Authorization","Bearer "+token))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(60060));
        mockMvc.perform(get("/api/v1/app/alarms/"+visible).header("Authorization","Bearer "+login("bob"))).andExpect(status().isNotFound());
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE app_user_device SET status='CLOSED' WHERE device_id=? AND app_user_id=?",deviceReadOnly,userPrimary); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/alarms/"+visible).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/alarms").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/app/alarms").header("Authorization","Bearer "+consoleToken())).andExpect(status().isUnauthorized());
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?",userPrimary); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/alarms").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }

    @Test
    void alarmHistoryRejectsInvalidWindowsAndEnums() throws Exception {
        String token=login("alice");
        for (var param : Map.of("limit","51","severity","URGENT","conditionState","PENDING","from","2026-10-01T00:00:00Z","cursor","x".repeat(2049)).entrySet())
            mockMvc.perform(get("/api/v1/app/alarms").param(param.getKey(),param.getValue()).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        for (String end : new String[]{"2026-01-01T00:00:00Z","2028-01-01T00:00:00Z"})
            mockMvc.perform(get("/api/v1/app/alarms").param("from","2026-01-01T00:00:00Z").param("to",end).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 列表与分页

    @Test
    @DisplayName("列表只返回当前用户的绑定设备")
    void listOnlyReturnsBoundDevices() throws Exception {
        String token = login("alice");

        MvcResult result = mockMvc.perform(get("/api/v1/app/devices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("items").size()).isEqualTo(3);
        assertThat(body.get("hasMore").asBoolean()).isFalse();
        Set<String> ids = new LinkedHashSet<>();
        body.get("items").forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(
                devicePrimary.toString(), deviceMember.toString(), deviceReadOnly.toString());
    }

    @Test
    @DisplayName("游标分页 limit=2 返回 hasMore 与 nextCursor")
    void cursorPagination() throws Exception {
        String token = login("alice");

        MvcResult first = mockMvc.perform(get("/api/v1/app/devices")
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("items").size()).isEqualTo(2);
        assertThat(firstBody.get("hasMore").asBoolean()).isTrue();
        String nextCursor = firstBody.get("nextCursor").asText();
        assertThat(nextCursor).isNotBlank();

        MvcResult second = mockMvc.perform(get("/api/v1/app/devices")
                        .param("limit", "2")
                        .param("cursor", nextCursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("items").size()).isEqualTo(1);
        assertThat(secondBody.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void deviceSearchFiltersBeforePaginationAndUsesLiteralWildcards() throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("UPDATE dev_device SET name='公共灯',status='ONLINE' WHERE id IN (?,?)", devicePrimary, deviceMember);
            jdbcTemplate.update("UPDATE dev_device SET name='公共灯',status='OFFLINE' WHERE id=?", deviceReadOnly);
            jdbcTemplate.update("UPDATE dev_device SET name='未授权公共灯',status='ONLINE' WHERE id=?", deviceUnbound);
        } finally { TenantContext.clear(); }
        String token = login("alice");
        var first = mockMvc.perform(get("/api/v1/app/devices").param("q", "公共灯").param("status", "ONLINE").param("limit", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        var firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        var second = mockMvc.perform(get("/api/v1/app/devices").param("q", "公共灯").param("status", "ONLINE").param("limit", "1")
                        .param("cursor", firstBody.path("nextCursor").asText()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false)).andReturn();
        assertThat(objectMapper.readTree(second.getResponse().getContentAsString()).path("items").get(0).path("id").asText())
                .isNotEqualTo(firstBody.path("items").get(0).path("id").asText());
        for (String literal : java.util.List.of("%", "_", "!")) {
            mockMvc.perform(get("/api/v1/app/devices").param("q", literal).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        }
        mockMvc.perform(get("/api/v1/app/devices").param("status", "BAD").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/app/devices").param("q", "x".repeat(101)).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/app/devices").param("cursor", "invalid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicDetailExposesReportIndependentlyOfOnlineAndRejectsRevokedBinding() throws Exception {
        Instant report = Instant.parse("2026-10-09T10:00:00Z");
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("UPDATE dev_device SET last_data_report_at=?,last_online_at=NULL WHERE id=?",
                    Timestamp.from(report), deviceReadOnly);
        } finally { TenantContext.clear(); }
        String token = login("alice");
        mockMvc.perform(get("/api/v1/app/devices/" + deviceReadOnly).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceTypeName").isString())
                .andExpect(jsonPath("$.lastDataReportAt").value(report.toString()))
                .andExpect(jsonPath("$.lastOnlineAt").isEmpty());
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try { jdbcTemplate.update("UPDATE app_user_device SET status='CLOSED' WHERE app_user_id=? AND device_id=?", userPrimary, deviceReadOnly); }
        finally { TenantContext.clear(); }
        mockMvc.perform(get("/api/v1/app/devices/" + deviceReadOnly).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    /** App权限链保留超过JS安全整数的PG序号，历史未知来源不借用当前绑定。 */
    @Test
    void currentValuesPreserveReportedRevisionAsText() throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    UPDATE dev_shadow SET reported_sequence = 9007199254740993,
                        reported_revisions = '{"temperature":"9007199254740993"}'::jsonb
                    WHERE device_id = ?
                    """, deviceReadOnly);
        } finally {
            TenantContext.clear();
        }
        mockMvc.perform(get("/api/v1/app/devices/{id}/current-values", deviceReadOnly)
                        .param("keys", "temperature")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportedRevision").value("9007199254740993"))
                .andExpect(jsonPath("$[0].thingModelVersionId").doesNotExist());
    }

    // ---------------------------------------------------------------- 读：任意关系角色可读

    @Test
    @DisplayName("READ_ONLY 关系可读详情、当前值与历史")
    void readOnlyRelationCanRead() throws Exception {
        String token = login("alice");

        // 详情
        mockMvc.perform(get("/api/v1/app/devices/{id}", deviceReadOnly)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deviceReadOnly.toString()));

        // 当前值
        mockMvc.perform(get("/api/v1/app/devices/{id}/current-values", deviceReadOnly)
                        .param("keys", "temperature")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].propertyKey").value("temperature"))
                .andExpect(jsonPath("$[0].value").value(21.0))
                .andExpect(jsonPath("$[0].reportedRevision").doesNotExist())
                .andExpect(jsonPath("$[0].thingModelVersionId").doesNotExist());

        // 历史
        Instant to = Instant.now();
        Instant from = to.minusSeconds(3600);
        mockMvc.perform(get("/api/v1/app/devices/{id}/telemetry/history", deviceReadOnly)
                        .param("propertyKey", "temperature")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    // ---------------------------------------------------------------- 控制：关系角色唯一闸

    @Test
    @DisplayName("READ_ONLY 关系下发命令返回 60011")
    void readOnlyRelationDeniesControl() throws Exception {
        String token = login("alice");

        mockMvc.perform(post("/api/v1/app/devices/{id}/commands", deviceReadOnly)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "cmd-readonly-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("commandKey", "restart", "input", Map.of("param", 1)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(60011));
    }

    @Test
    @DisplayName("OBSERVER 项目角色 + PRIMARY 关系可下发命令")
    void observerPrimaryRelationCanControl() throws Exception {
        String token = login("bob");

        mockMvc.perform(post("/api/v1/app/devices/{id}/commands", deviceObserver)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "cmd-observer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("commandKey", "restart", "input", Map.of("param", 1)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandKey").value("restart"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // ---------------------------------------------------------------- 不存在统一 404

    @Test
    @DisplayName("未绑定设备返回 60010")
    void unboundDeviceNotFound() throws Exception {
        String token = login("alice");

        mockMvc.perform(get("/api/v1/app/devices/{id}", deviceUnbound)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(60010));
    }

    @Test
    @DisplayName("跨项目设备返回 60010")
    void crossProjectDeviceNotFound() throws Exception {
        String token = login("alice");

        mockMvc.perform(get("/api/v1/app/devices/{id}", deviceOtherProject)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(60010));
    }

    // ---------------------------------------------------------------- 命令提交 + 状态回读

    @Test
    @DisplayName("PRIMARY 关系提交命令后可回读状态")
    void submitThenReadStatus() throws Exception {
        String token = login("alice");

        MvcResult submitted = mockMvc.perform(post("/api/v1/app/devices/{id}/commands", devicePrimary)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "cmd-primary-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("commandKey", "restart", "input", Map.of("param", 1)))))
                .andExpect(status().isAccepted())
                .andReturn();

        String commandId = objectMapper.readTree(submitted.getResponse().getContentAsString())
                .get("commandId").asText();

        mockMvc.perform(get("/api/v1/app/devices/{id}/commands/{commandId}", devicePrimary, commandId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // ---------------------------------------------------------------- 令牌边界

    @Test
    @DisplayName("控制台令牌打 App 端点返回 60009")
    void consoleTokenDenied() throws Exception {
        mockMvc.perform(get("/api/v1/app/devices")
                        .header("Authorization", "Bearer " + consoleToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60009));
    }

    @Test
    @DisplayName("无令牌打 App 端点返回 60009")
    void noTokenDenied() throws Exception {
        mockMvc.perform(get("/api/v1/app/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60009));
    }

    // ---------------------------------------------------------------- 夹具

    private UUID newTenant(String name) {
        UUID id = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name,quota_policy_id) VALUES (?, ?, (SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE'))", id, name);
        tenantIds.add(id);
        return id;
    }

    private UUID newProject(UUID tenantId, String key) {
        UUID id = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, id, tenantId, "项目-" + id, key);
        projectTenants.put(id, tenantId);
        return id;
    }

    private UUID newUser(UUID tenantId, String username) {
        UUID id = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, id, tenantId, username, passwordEncoder.encode(PASSWORD));
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private void addRole(UUID tenantId, UUID projectId, UUID userId, String role) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                    VALUES (?, ?, ?, ?, ?)
                    """, Uuid7.generate(), tenantId, projectId, userId, role);
        } finally {
            TenantContext.clear();
        }
    }

    private void addBinding(UUID tenantId, UUID projectId, UUID userId, UUID deviceId, String relationRole) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Uuid7.generate(), tenantId, projectId, userId, deviceId, relationRole);
        } finally {
            TenantContext.clear();
        }
    }

    /** 设备三表 + 物模型定义均受项目 RLS 保护，写入前先套上项目范围。 */
    private UUID seedDeviceType(UUID tenantId, UUID projectId, String typeKey) {
        UUID id = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                    VALUES (?, ?, ?, ?, ?, 'STANDARD', 'DIRECT', 'PUBLISHED')
                    """, id, tenantId, projectId, typeKey, "类型-" + typeKey);
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private UUID seedDevice(UUID tenantId, UUID projectId, UUID typeId, String deviceKey) {
        UUID id = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
                    """, id, tenantId, projectId, typeId, deviceKey, "设备-" + deviceKey);
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private void seedPropertyDefinition(UUID tenantId, UUID projectId, UUID typeId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_property_definition
                        (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type)
                    VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')
                    """, Uuid7.generate(), tenantId, projectId, typeId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 命令定义 input_schema 留空，让命令输入校验跳过（本片不关注 Schema 校验）。 */
    private void seedCommandDefinition(UUID tenantId, UUID projectId, UUID typeId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, timeout_seconds)
                    VALUES (?, ?, ?, ?, 'restart', '重启', 30)
                    """, Uuid7.generate(), tenantId, projectId, typeId);
        } finally {
            TenantContext.clear();
        }
    }

    private void seedShadow(UUID tenantId, UUID projectId, UUID deviceId, double temperature) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            String ts = Instant.parse("2026-08-18T00:00:00Z").toString();
            jdbcTemplate.update("""
                    INSERT INTO dev_shadow (device_id, tenant_id, project_id, reported, reported_at, version)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, 0)
                    """, deviceId, tenantId, projectId,
                    "{\"temperature\": " + temperature + "}",
                    "{\"temperature\": \"" + ts + "\"}");
        } finally {
            TenantContext.clear();
        }
    }

    private void seedPropertyPoints(UUID tenantId, UUID projectId, UUID deviceId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    INSERT INTO ts_property_point (project_id, device_id, property_key, ts, message_id, value_double)
                    VALUES (?, ?, 'temperature', ?, ?, ?)
                    """, projectId, deviceId, Timestamp.from(now.minusSeconds(100)), Uuid7.generate(), 20.0);
            jdbcTemplate.update("""
                    INSERT INTO ts_property_point (project_id, device_id, property_key, ts, message_id, value_double)
                    VALUES (?, ?, 'temperature', ?, ?, ?)
                    """, projectId, deviceId, Timestamp.from(now.minusSeconds(50)), Uuid7.generate(), 22.0);
        } finally {
            TenantContext.clear();
        }
    }

    private static String uniqueProjectKey() {
        return "pk" + Uuid7.generate().toString().replace("-", "").substring(0, 16);
    }

    /** 使用真实登录限流，同一项目夹具共享来源地址，避免其他夹具耗尽默认 IP 配额。 */
    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(fixtureClientIp());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("projectKey", projectKey, "username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** 将项目 UUID 低 96 位映射到文档 IPv6 网段，保留真实 IP 限流而隔离不同夹具。 */
    private String fixtureClientIp() {
        String suffix = projectId.toString().replace("-", "").substring(8);
        return "2001:db8:" + suffix.substring(0, 4) + ":" + suffix.substring(4, 8)
                + ":" + suffix.substring(8, 12) + ":" + suffix.substring(12, 16)
                + ":" + suffix.substring(16, 20) + ":" + suffix.substring(20, 24);
    }

    /** 用控制台密钥签一枚控制台令牌（subject 是任意 accountId，供互斥测试）。 */
    private String consoleToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(consoleJwtProperties.issuer())
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("tid", tenantId.toString())
                .claim("pid", projectId.toString())
                .build();
        return consoleJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
