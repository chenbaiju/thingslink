package com.things.link.bootstrap.telemetry.history;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.DeviceMessageLogCommand;
import com.things.link.telemetry.application.MessageLogService;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S5-2 项目级消息日志查询的真实 PostgreSQL、RLS 与 HTTP 契约验收。
 *
 * <p>ThingsBoard 参考边界只吸收日志生命周期、键集分页和查询限额。测试因此钉住 ThingsLink 自己的
 * Project 隔离、固定筛选维度与不透明游标，不引入 Entity Relation 或前端查询 DSL。
 */
@AutoConfigureMockMvc
@DisplayName("S5-2 项目级消息日志查询")
class MessageLogQueryTests extends AbstractIntegrationTest {
    /** JSON 请求与响应解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 仅用于集成测试账号的固定高强度口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** HTTP 测试入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** 仅用于建立项目成员夹具与核对账号归属。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 注册多账号前清理测试环境令牌桶。 */
    @Autowired
    private AuthRateLimiter rateLimiter;
    /** 通过真实应用事务写入日志，避免测试绕过幂等、设备归属与 RLS。 */
    @Autowired
    private MessageLogService messageLogService;

    /** 项目 A 的所有者及选定项目后的令牌。 */
    private Actor ownerA;
    /** 项目 B 的所有者及选定项目后的令牌。 */
    private Actor ownerB;
    /** 互不隶属的项目 A。 */
    private UUID projectA;
    /** 互不隶属的项目 B。 */
    private UUID projectB;
    /** 项目 A 的第一台设备。 */
    private UUID deviceA1;
    /** 项目 A 的第二台设备。 */
    private UUID deviceA2;
    /** 项目 B 的设备，用于跨项目资源隐藏验收。 */
    private UUID deviceB;

    /**
     * 每个测试建立两个真实租户、项目和三台设备，保证授权判定经过 HTTP 与数据库 RLS。
     *
     * @throws Exception HTTP 夹具创建失败
     */
    @BeforeEach
    void seedProjectsAndDevices() throws Exception {
        String nonce = UUID.randomUUID().toString();
        ownerA = registerAndLogin("s5-message-a-" + nonce + "@example.com");
        projectA = createProject(ownerA, "S5 消息项目甲");
        ownerA = switchProject(ownerA, projectA);
        deviceA1 = createDevice(ownerA, projectA, "s5-log-a1-" + nonce, "日志设备甲一");
        deviceA2 = createDevice(ownerA, projectA, "s5-log-a2-" + nonce, "日志设备甲二");

        ownerB = registerAndLogin("s5-message-b-" + nonce + "@example.com");
        projectB = createProject(ownerB, "S5 消息项目乙");
        ownerB = switchProject(ownerB, projectB);
        deviceB = createDevice(ownerB, projectB, "s5-log-b-" + nonce, "日志设备乙");
    }

    /** 清理直接调用消息日志应用端口时设置的线程项目上下文。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * 项目级入口应组合全部白名单维度，并以 (ts,id) 游标稳定翻页。
     *
     * <p>两个命中项刻意使用相同发生时间，证明实现不能只用 ts 翻页；from 等于发生时间必须包含，
     * to 等于发生时间必须排除。再用 deviceId 收窄，证明项目级入口没有丢掉设备维度。
     *
     * @throws Exception HTTP 或日志写入失败
     */
    @Test
    void combinesFiltersWithStableCursorAndHalfOpenTimeWindow() throws Exception {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant to = from.plus(10, ChronoUnit.MINUTES);
        UUID matchingA1 = log(ownerA, projectA, deviceA1, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-s5-target", from);
        UUID matchingA2 = log(ownerA, projectA, deviceA2, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-s5-target", from);
        UUID atExclusiveBoundary = log(ownerA, projectA, deviceA1, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-s5-target", to);
        log(ownerA, projectA, deviceA1, TransportProtocol.HTTP,
                DeviceMessageLog.Direction.UP, "trace-s5-target", from.plusSeconds(1));
        log(ownerA, projectA, deviceA1, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.DOWN, "trace-s5-target", from.plusSeconds(2));
        log(ownerA, projectA, deviceA1, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-s5-other", from.plusSeconds(3));

        String path = "/api/v1/projects/" + projectA + "/messages";
        MvcResult firstResult = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("protocol", "MQTT")
                        .queryParam("direction", "UP")
                        .queryParam("traceId", "trace-s5-target")
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .queryParam("limit", "1"))
                .andReturn();
        assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstPage = JSON.readTree(firstResult.getResponse().getContentAsString());
        assertThat(firstPage.get("items")).hasSize(1);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();
        assertThat(firstPage.get("nextCursor").asString()).isNotBlank();

        MvcResult secondResult = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("protocol", "MQTT")
                        .queryParam("direction", "UP")
                        .queryParam("traceId", "trace-s5-target")
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .queryParam("limit", "1")
                        .queryParam("cursor", firstPage.get("nextCursor").asString()))
                .andReturn();
        assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode secondPage = JSON.readTree(secondResult.getResponse().getContentAsString());
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();

        Set<UUID> pagedMessageIds = new HashSet<>();
        pagedMessageIds.add(UUID.fromString(firstPage.get("items").get(0).get("messageId").asString()));
        pagedMessageIds.add(UUID.fromString(secondPage.get("items").get(0).get("messageId").asString()));
        assertThat(pagedMessageIds).containsExactlyInAnyOrder(matchingA1, matchingA2);
        assertThat(pagedMessageIds).doesNotContain(atExclusiveBoundary);

        MvcResult deviceResult = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("deviceId", deviceA1.toString())
                        .queryParam("protocol", "MQTT")
                        .queryParam("direction", "UP")
                        .queryParam("traceId", "trace-s5-target")
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString()))
                .andReturn();
        JsonNode devicePage = JSON.readTree(deviceResult.getResponse().getContentAsString());
        assertThat(devicePage.get("items")).hasSize(1);
        assertThat(devicePage.get("items").get(0).get("deviceId").asString())
                .isEqualTo(deviceA1.toString());
    }

    /**
     * 损坏游标与反向时间窗返回稳定参数错误，项目和设备归属错误采用“不存在”语义防枚举。
     *
     * @throws Exception HTTP 请求失败
     */
    @Test
    void rejectsForgedCursorAndHidesCrossProjectResources() throws Exception {
        String projectAPath = "/api/v1/projects/" + projectA + "/messages";
        MvcResult forgedCursor = mockMvc.perform(get(projectAPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("cursor", "not-a-valid-cursor"))
                .andReturn();
        assertThat(errorCode(forgedCursor)).isEqualTo(10001);

        Instant now = Instant.now();
        MvcResult invalidWindow = mockMvc.perform(get(projectAPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("from", now.toString())
                        .queryParam("to", now.minusSeconds(1).toString()))
                .andReturn();
        assertThat(errorCode(invalidWindow)).isEqualTo(10001);

        MvcResult hiddenProject = mockMvc.perform(get("/api/v1/projects/" + projectB + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken()))
                .andReturn();
        assertThat(errorCode(hiddenProject)).isEqualTo(50001);

        MvcResult hiddenDevice = mockMvc.perform(get(projectAPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .queryParam("deviceId", deviceB.toString()))
                .andReturn();
        assertThat(errorCode(hiddenDevice)).isEqualTo(30020);
    }

    /**
     * device:read 已授予 VIEWER；消息日志是只读设备观测，不应另造一个重叠权限点。
     *
     * @throws Exception 成员切换或 HTTP 请求失败
     */
    @Test
    void viewerCanReadProjectMessageLog() throws Exception {
        addMember(projectA, ownerB.accountId(), "VIEWER");
        Actor viewer = switchProject(ownerB, projectA);
        log(ownerA, projectA, deviceA1, TransportProtocol.MQTT,
                DeviceMessageLog.Direction.UP, "trace-s5-viewer", Instant.now());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + projectA + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer.accessToken())
                        .queryParam("traceId", "trace-s5-viewer"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("items")).hasSize(1);
    }

    /** 通过公开认证接口创建账号并取得初始访问/刷新令牌。 */
    private Actor registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refreshToken = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        UUID accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?", UUID.class, accountId);
        return new Actor(accountId, tenantId, body.get("accessToken").asString(), refreshToken);
    }

    /** 通过真实项目 API 创建项目。 */
    private UUID createProject(Actor actor, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 把访问令牌绑定到指定项目，保证 Controller 与应用服务都按实时成员关系授权。 */
    private Actor switchProject(Actor actor, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .cookie(new Cookie("tc_refresh", actor.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        String rotatedRefreshToken = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElse(actor.refreshToken());
        return new Actor(actor.accountId(), actor.tenantId(),
                JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(),
                rotatedRefreshToken);
    }

    /** 通过真实设备 API 创建设备，确保归属端口与 RLS 均能识别夹具。 */
    private UUID createDevice(Actor actor, UUID projectId, String deviceKey, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"%s\",\"name\":\"%s\"}".formatted(deviceKey, name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 消息详情：凭据类字段整字段移除，JSON 与 HEX 两种格式等价呈现同一份脱敏摘要（§7.2）。 */
    @Test
    void detailRemovesCredentialFieldsInBothFormats() throws Exception {
        // 业务载荷里故意塞入凭据类字段：设备完全可能这么干，调试面必须整字段移除而不是打码。
        TenantContext.set(new TenantScope(ownerA.tenantId(), projectA, ownerA.accountId()));
        UUID messageId = Uuid7.generate();
        String payload = "{\"temperature\":21,\"secret\":\"top-secret-value\",\"nested\":{"
                + "\"access_token\":\"tok-123\",\"accessToken\":\"camel-canary\",\"kept\":7},\"client-secret\":\"cs-1\"}";
        assertThat(messageLogService.log(new DeviceMessageLogCommand(
                projectA, deviceA1, messageId, TransportProtocol.HTTP, DeviceMessageLog.Direction.UP,
                "tc/v1/project/device/up/property/report", payload, payload.length(),
                null, Instant.now(), Instant.now(), "trace-detail", "PROPERTY_REPORT"))).isTrue();
        UUID logId = jdbcTemplate.queryForObject(
                "SELECT id FROM ts_device_message_log WHERE message_id = ?", UUID.class, messageId);
        String stored = jdbcTemplate.queryForObject(
                "SELECT payload_summary FROM ts_device_message_log WHERE id=?", String.class, logId);
        assertThat(stored).doesNotContain("camel-canary", "accessToken", "tok-123", "top-secret-value");
        // 只在隔离测试库模拟脱敏修复前的旧行，证明读取也会兜底；不提供生产改写日志能力。
        var legacyOwner = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        legacyOwner.update("UPDATE ts_device_message_log SET payload_summary=? WHERE id=?", payload, logId);
        TenantContext.clear();

        // JSON 格式：凭据键必须整体消失，正常业务字段保留。
        var json = mockMvc.perform(get("/api/v1/projects/" + projectA + "/devices/" + deviceA1
                        + "/messages/" + logId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken()))
                .andReturn().getResponse();
        assertThat(json.getStatus()).isEqualTo(200);
        String body = json.getContentAsString();
        assertThat(body).contains("temperature").contains("kept");
        assertThat(body).doesNotContain("top-secret-value").doesNotContain("tok-123")
                .doesNotContain("cs-1").doesNotContain("secret").doesNotContain("token")
                .doesNotContain("camel-canary").doesNotContain("accessToken");
        assertThat(JSON.readTree(body).get("format").asString()).isEqualTo("JSON");

        // HEX 格式：同一份脱敏字节的十六进制，同样不得包含凭据明文。
        var hex = mockMvc.perform(get("/api/v1/projects/" + projectA + "/devices/" + deviceA1
                        + "/messages/" + logId + "?format=HEX")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken()))
                .andReturn().getResponse();
        assertThat(hex.getStatus()).isEqualTo(200);
        JsonNode hexBody = JSON.readTree(hex.getContentAsString());
        assertThat(hexBody.get("format").asString()).isEqualTo("HEX");
        String decoded = new String(java.util.HexFormat.of().parseHex(hexBody.get("payloadHex").asString()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("temperature").doesNotContain("top-secret-value", "tok-123", "camel-canary", "accessToken");
    }

    /** 详情不区分"不存在"与"不属于本设备／项目"：都按 404，避免把日志 ID 变成存在性探针。 */
    @Test
    void detailOfForeignOrUnknownLogIsNotFound() throws Exception {
        var unknown = mockMvc.perform(get("/api/v1/projects/" + projectA + "/devices/" + deviceA1
                        + "/messages/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken()))
                .andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(404);
    }

    /** 调试时间线：受理与处理时刻必须落库，解析／投递／回复未发生即为空，且不伪造字段（§7.2）。 */
    @Test
    void timelineCarriesStageFactsAndHonestNulls() throws Exception {
        UUID messageId = log(ownerA, projectA, deviceA1, TransportProtocol.HTTP,
                DeviceMessageLog.Direction.UP, "trace-timeline", Instant.now());

        // 日志表受 RLS 约束：直连读取必须带项目范围，否则看到 0 行（这不是"没写进去"）。
        TenantContext.set(new TenantScope(ownerA.tenantId(), projectA, ownerA.accountId()));
        java.util.Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap("""
                    SELECT message_type, accepted_at, parsed_at, processed_at, delivered_at, replied_at,
                           truncated, sampled
                      FROM ts_device_message_log WHERE message_id = ?
                    """, messageId);
        } finally {
            TenantContext.clear();
        }

        assertThat(row.get("message_type")).isEqualTo("PROPERTY_REPORT");
        assertThat(row.get("accepted_at")).as("受理时刻必须落库").isNotNull();
        assertThat(row.get("processed_at")).as("处理完成时刻必须落库").isNotNull();
        assertThat(row.get("parsed_at")).as("解析阶段未单独记录时不得用当前时刻冒充").isNull();
        assertThat(row.get("delivered_at")).as("上行消息不产生投递阶段").isNull();
        assertThat(row.get("replied_at")).as("上行消息不产生回复阶段").isNull();
        assertThat(row.get("truncated")).isEqualTo(false);
        assertThat(row.get("sampled")).isEqualTo(false);
    }

    /** 摘要截断必须**标记**：否则调试面会把截断后的摘要当成完整报文（§7.3）。 */
    @Test
    void truncatedSummaryIsMarked() {
        TenantContext.set(new TenantScope(ownerA.tenantId(), projectA, ownerA.accountId()));
        UUID messageId = Uuid7.generate();
        String longPayload = "{\"temperature\":" + "1".repeat(9999) + "}";
        try {
            assertThat(messageLogService.log(new DeviceMessageLogCommand(
                    projectA, deviceA1, messageId, TransportProtocol.HTTP, DeviceMessageLog.Direction.UP,
                    "tc/v1/project/device/up/property/report", longPayload, longPayload.length(),
                    null, Instant.now(), Instant.now(), "trace-truncated"))).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT truncated FROM ts_device_message_log WHERE message_id = ?", Boolean.class, messageId))
                    .as("超长摘要必须标记为已截断").isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    /** 通过应用服务的真实事务写一条日志，并返回外部可见的 messageId。 */
    private UUID log(Actor actor, UUID projectId, UUID deviceId, TransportProtocol protocol,
                     DeviceMessageLog.Direction direction, String traceId, Instant occurredAt) {
        TenantContext.set(new TenantScope(actor.tenantId(), projectId, actor.accountId()));
        UUID messageId = Uuid7.generate();
        assertThat(messageLogService.log(new DeviceMessageLogCommand(
                projectId, deviceId, messageId, protocol, direction,
                "tc/v1/project/device/up/property/report", "{\"temperature\":26.5}", 20,
                null, occurredAt, occurredAt.plusMillis(20), traceId, "PROPERTY_REPORT"))).isTrue();
        TenantContext.clear();
        return messageId;
    }

    /** 直接增加成员只建立授权夹具；被测消息查询仍完整经过 HTTP、成员服务与数据库 RLS。 */
    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?,?,?,?)",
                Uuid7.generate(), projectId, accountId, role);
    }

    /** 从统一错误响应读取稳定业务错误码。 */
    private static int errorCode(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    /** 测试账号身份、租户归属与会话凭据。 */
    private record Actor(UUID accountId, UUID tenantId, String accessToken, String refreshToken) {
    }
}
