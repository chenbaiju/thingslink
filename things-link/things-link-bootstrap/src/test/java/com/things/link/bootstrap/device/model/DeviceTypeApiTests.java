package com.things.link.bootstrap.device.model;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import javax.sql.DataSource;
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

import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 设备类型 API 端到端测试。
 *
 * <p>测试放在 bootstrap 而不是 device：需要同时经过 iam 登录、project 项目切换和
 * device 接口，业务模块之间不能为了测试互相形成循环依赖。
 */
@AutoConfigureMockMvc
@DisplayName("设备类型接口（S2-1/S2-2）")
class DeviceTypeApiTests extends AbstractIntegrationTest {
    /** JSON 编解码器。 */ private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试口令满足当前 10 位最低强度。 */ private static final String PASSWORD = "correct-horse-battery-staple";
    /** MockMvc 真实过滤器链入口。 */ @Autowired private MockMvc mockMvc;
    /** 仅用于准备跨角色关系与核验数据库结果。 */ @Autowired private JdbcTemplate jdbcTemplate;
    /** 并发冻结测试需要两条独立 PostgreSQL 连接验证真实行锁。 */ @Autowired private DataSource dataSource;
    /** 注册/登录限流器有进程级状态，每个测试必须清理。 */ @Autowired private AuthRateLimiter rateLimiter;
    /** OWNER 登录态。 */ private Login owner;
    /** VIEWER 登录态。 */ private Login viewer;
    /** VIEWER 的账号 ID。 */ private UUID viewerAccountId;

    /** 每个测试从干净的项目与设备数据开始，避免唯一键和限流状态互相影响。 */
    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        jdbcTemplate.update("DELETE FROM sys_project_member");
        // dev_type 受 RLS 保护，测试准备阶段没有项目上下文，不能直接 DELETE。
        // 硬删除项目由外键级联清理设备类型；生产业务删除仍是软删除，不会触发本级联。
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
        owner = registerAndLogin("owner-device@example.com");
        viewer = registerAndLogin("viewer-device@example.com");
        viewerAccountId = accountId("viewer-device@example.com");
    }

    /** OWNER 创建后能立即从同一项目列表读取草稿。 */
    @Test
    void ownerCreatesAndListsDeviceType() throws Exception {
        UUID projectId = createProject(owner, "设备项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult created = createType(scoped, projectId, "temperature_sensor", "温度传感器");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asString()).isEqualTo("DRAFT");

        JsonNode list = listTypes(scoped, projectId);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("typeKey").asString()).isEqualTo("temperature_sensor");
    }

    /**
     * G1-C3c 兼容窗口：旧数组在第 201 条时显式冲突，新接口即使创建时刻相同也不重不漏。
     */
    @Test
    void keysetPagesEqualTimestampsAndLegacyListRejectsTwoHundredFirstRow() throws Exception {
        UUID projectId = createProject(owner, "201 类型项目");
        Login scoped = switchProject(owner, projectId);
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        Instant sameCreatedAt = Instant.parse("2026-08-22T00:00:00Z");
        TenantContext.set(new TenantScope(tenantId, projectId, accountId("owner-device@example.com")));
        try {
            for (int index = 0; index < 201; index++) {
                jdbcTemplate.update("""
                        INSERT INTO dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol,
                             network_type, version, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'DIRECT', 'STANDARD', 'WIFI', 1, 'DRAFT', ?, ?)
                        """, Uuid7.generate(), tenantId, projectId, "paged_type_" + index,
                        "分页类型 " + index, Timestamp.from(sameCreatedAt), Timestamp.from(sameCreatedAt));
            }
        } finally {
            TenantContext.clear();
        }

        MvcResult legacy = getTypes(scoped, projectId);
        assertThat(legacy.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(legacy)).isEqualTo(30050);

        Set<String> ids = new HashSet<>();
        String cursor = null;
        do {
            var request = get("/api/v1/projects/" + projectId + "/device-types/search")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                    .queryParam("limit", "37");
            if (cursor != null) {
                request.queryParam("cursor", cursor);
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode page = JSON.readTree(result.getResponse().getContentAsString());
            for (JsonNode item : page.get("items")) {
                assertThat(ids.add(item.get("id").asString())).as("跨页 UUID 不得重复").isTrue();
            }
            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
        } while (cursor != null);
        assertThat(ids).hasSize(201);

        MvcResult invalid = mockMvc.perform(get("/api/v1/projects/" + projectId + "/device-types/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("cursor", "forged"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(invalid)).isEqualTo(10001);
    }

    /**
     * G1-C1b 兼容契约（ADR 0042）：`createDefaultDataStream`/`defaultDataStreamFormat` 已随控制面下线删除，
     * 旧客户端继续发送时请求成功、字段被忽略、响应不回显，且**不再**随类型创建 `dev_data_stream` 行。
     */
    @Test
    void ignoresRemovedDefaultDataStreamFieldsOnDeviceType() throws Exception {
        UUID projectId = createProject(owner, "默认流下线项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"dtu","name":"DTU","deviceKind":"DIRECT",\
                                 "payloadProtocol":"MODBUS_RTU_PASSTHROUGH","networkType":"CELLULAR_4G",\
                                 "createDefaultDataStream":true,"defaultDataStreamFormat":"MODBUS_RTU"}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("defaultDataStreamFormat"))
                .as("响应不得回显已删除字段（ADR 0042）").isFalse();
        UUID typeId = UUID.fromString(body.get("id").asString());
        // 设备类型创建后，数据流控制面已下线，不得再写出默认数据流行。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_data_stream WHERE device_type_id = ?", Integer.class, typeId))
                .as("控制面下线后不得创建默认数据流").isZero();
    }

    /** 标识符格式由 Bean Validation 拦截，不让非法 key 进入数据库。 */
    @Test
    void rejectsInvalidTypeKey() throws Exception {
        UUID projectId = createProject(owner, "校验项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult result = createType(scoped, projectId, "Temperature-Sensor", "温度传感器");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo(10001);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_type", Integer.class)).isZero();
    }

    /** VIEWER 可读但不可创建，前端隐藏按钮之外服务端仍必须返回 30002。 */
    @Test
    void viewerReadsButCannotCreate() throws Exception {
        UUID projectId = createProject(owner, "协作项目");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        assertThat(listTypes(viewerScoped, projectId).isEmpty()).isTrue();
        MvcResult result = createType(viewerScoped, projectId, "sensor", "传感器");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(result)).isEqualTo(30002);
    }

    /** 项目 A 的令牌不能通过改路径读取项目 B，且响应不泄露 B 是否存在。 */
    @Test
    void projectIsolationRejectsOtherProject() throws Exception {
        UUID projectA = createProject(owner, "项目 A");
        UUID projectB = createProject(viewer, "项目 B");
        Login tokenA = switchProject(owner, projectA);
        Login tokenB = switchProject(viewer, projectB);
        assertThat(createType(tokenB, projectB, "private_sensor", "私有传感器")
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult result = getTypes(tokenA, projectB);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(result)).isEqualTo(30001);
        assertThat(listTypes(tokenA, projectA)).isEmpty();
    }

    /** OWNER 可修改草稿并软删除，删除后列表与详情定位均视为不存在。 */
    @Test
    void ownerUpdatesAndDeletesDraft() throws Exception {
        UUID projectId = createProject(owner, "维护项目");
        Login scoped = switchProject(owner, projectId);
        UUID id = UUID.fromString(JSON.readTree(createType(scoped, projectId, "sensor", "传感器")
                .getResponse().getContentAsString()).get("id").asString());
        MvcResult updated = updateType(scoped, projectId, id, "gateway_sensor", "网关传感器");
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(updated.getResponse().getContentAsString()).get("typeKey").asString())
                .isEqualTo("gateway_sensor");
        MvcResult deleted = deleteType(scoped, projectId, id);
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        assertThat(listTypes(scoped, projectId)).isEmpty();
        // 请求结束后项目上下文已清除，RLS 按 fail-closed 隐藏 dev_type；通过公开列表验证删除结果。
    }

    /** VIEWER 的写请求由服务端拒绝，不能依赖前端隐藏操作按钮。 */
    @Test
    void viewerCannotUpdateOrDelete() throws Exception {
        UUID projectId = createProject(owner, "只读项目");
        Login ownerScoped = switchProject(owner, projectId);
        UUID id = UUID.fromString(JSON.readTree(createType(ownerScoped, projectId, "sensor", "传感器")
                .getResponse().getContentAsString()).get("id").asString());
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        assertThat(errorCode(updateType(viewerScoped, projectId, id, "sensor_new", "新名称"))).isEqualTo(30004);
        assertThat(errorCode(deleteType(viewerScoped, projectId, id))).isEqualTo(30004);
    }

    /** 分类与接入协议不兼容时返回稳定业务码，数据库也有同一矩阵约束兜底。 */
    @Test
    void rejectsIncompatibleAccessProtocol() throws Exception {
        UUID projectId = createProject(owner, "协议项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"bad_gateway","name":"错误网关","deviceKind":"GATEWAY",\
                                 "payloadProtocol":"STANDARD","networkType":"ETHERNET"}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo(30006);
    }

    /** OWNER 可为草稿类型维护含量程的 Number 属性和带枚举值的 Enum 属性。 */
    @Test
    void ownerManagesPropertyDefinitions() throws Exception {
        UUID projectId = createProject(owner, "属性项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "thermostat");
        String path = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/properties";
        MvcResult number = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyKey":"temperature","name":"温度","accessType":"REPORT",\
                                 "dataType":"NUMBER","unit":"℃","decimalPlaces":2,\
                                 "minimumValue":-40,"maximumValue":125,"sortOrder":0}
                                """))
                .andReturn();
        assertThat(number.getResponse().getStatus()).isEqualTo(201);
        MvcResult enumeration = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyKey":"mode","name":"模式","accessType":"SHARED",\
                                 "dataType":"ENUM","enumOptions":["auto","manual"],"sortOrder":1}
                                """))
                .andReturn();
        assertThat(enumeration.getResponse().getStatus()).isEqualTo(201);
        MvcResult listed = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn();
        JsonNode properties = JSON.readTree(listed.getResponse().getContentAsString());
        assertThat(properties).hasSize(2);
        assertThat(properties.get(0).get("minimumValue").decimalValue()).isEqualByComparingTo("-40");
        assertThat(properties.get(1).get("enumOptions")).hasSize(2);
        UUID propertyId = UUID.fromString(properties.get(0).get("id").asString());
        assertThat(mockMvc.perform(delete(path + "/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
    }

    /** OWNER 可维护带结构化参数的事件定义，并通过同一接口读取完整参数 Schema。 */
    @Test
    void ownerManagesEventDefinitions() throws Exception {
        UUID projectId = createProject(owner, "事件项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "alarm_sensor");
        String path = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/events";
        MvcResult created = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"overheat","name":"过热告警","level":"WARNING",\
                                 "description":"温度超过安全阈值","sortOrder":0,"parameters":[\
                                 {"parameterKey":"temperature","name":"当前温度",\
                                  "dataType":"NUMBER","required":true,"sortOrder":0},\
                                 {"parameterKey":"severity","name":"告警级别","dataType":"ENUM",\
                                  "required":true,"enumOptions":["high","critical"],"sortOrder":1}]}
                                """))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode createdBody = JSON.readTree(created.getResponse().getContentAsString());
        assertThat(createdBody.get("level").asString()).isEqualTo("WARNING");
        assertThat(createdBody.get("parameters")).hasSize(2);
        UUID eventId = UUID.fromString(createdBody.get("id").asString());

        MvcResult listed = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn();
        JsonNode events = JSON.readTree(listed.getResponse().getContentAsString());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("parameters").get(1).get("enumOptions")).hasSize(2);
        assertThat(mockMvc.perform(delete(path + "/" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
    }

    /**
     * G1-C1b 兼容契约（ADR 0042）：`defaultDataStreamTcpBound` 与 `tcpBound` 已随 D-033 冻结删除，
     * `createDefaultDataStream`/`defaultDataStreamFormat` 已随 G1-C1b 控制面下线删除。
     * 旧客户端继续发送这些字段时请求成功、字段被忽略、响应不回显、不落库、不产生业务效果。
     * 数据流端点级不可达的断言见 {@link DataStreamControlPlaneOfflineTests}。
     */
    @Test
    void ignoresRemovedDefaultDataStreamAndTcpBoundFieldsOnDeviceType() throws Exception {
        UUID projectId = createProject(owner, "兼容项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"compat_type","name":"兼容类型","deviceKind":"DIRECT",\
                                 "payloadProtocol":"STANDARD","networkType":"WIFI",\
                                 "createDefaultDataStream":true,"defaultDataStreamFormat":"JSON",\
                                 "defaultDataStreamTcpBound":true}
                                """))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("defaultDataStreamTcpBound"))
                .as("响应不得回显已删除字段（ADR 0042）").isFalse();
        assertThat(body.has("defaultDataStreamFormat"))
                .as("响应不得回显已删除字段（ADR 0042）").isFalse();
        UUID typeId = UUID.fromString(body.get("id").asString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_data_stream WHERE device_type_id = ?", Integer.class, typeId))
                .as("控制面下线后不得创建默认数据流").isZero();
    }

    /** 注册、标记邮箱验证并登录，返回访问令牌与刷新 Cookie。 */
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(registered.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    /** 创建项目并返回 ID。 */
    private UUID createProject(Login login, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 切换项目会轮换访问令牌，RLS 项目上下文来自新令牌。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .cookie(new Cookie("tc_refresh", login.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        return new Login(JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString(), login.refreshToken());
    }

    /** 直接插入成员关系只用于准备角色，不绕过被测设备接口。 */
    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, ?)
                """, Uuid7.generate(), projectId, accountId, role);
    }

    /** 按邮箱取测试账号 ID。 */
    private UUID accountId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
    }

    /** 调用创建设备类型接口。 */
    private MvcResult createType(Login login, UUID projectId, String typeKey, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"%s","name":"%s","deviceKind":"DIRECT",\
                                 "payloadProtocol":"STANDARD","networkType":"WIFI"}
                                """.formatted(typeKey, name)))
                .andReturn();
    }

    /** 创建类型并提取 ID。 */
    private UUID createdTypeId(Login login, UUID projectId, String typeKey) throws Exception {
        return UUID.fromString(JSON.readTree(createType(login, projectId, typeKey, typeKey)
                .getResponse().getContentAsString()).get("id").asString());
    }

    /** 调用设备类型列表接口。 */
    private MvcResult getTypes(Login login, UUID projectId) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andReturn();
    }

    /** 调用修改设备类型接口。 */
    private MvcResult updateType(Login login, UUID projectId, UUID id, String typeKey, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/projects/" + projectId + "/device-types/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey":"%s","name":"%s","deviceKind":"GATEWAY",\
                                 "payloadProtocol":"STANDARD_GATEWAY","networkType":"ETHERNET"}
                                """.formatted(typeKey, name)))
                .andReturn();
    }

    /** 调用软删除设备类型接口。 */
    private MvcResult deleteType(Login login, UUID projectId, UUID id) throws Exception {
        return mockMvc.perform(delete("/api/v1/projects/" + projectId + "/device-types/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andReturn();
    }

    /** 调用列表接口并要求成功。 */
    private JsonNode listTypes(Login login, UUID projectId) throws Exception {
        MvcResult result = getTypes(login, projectId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** 从标准错误响应中读取业务码。 */
    private int errorCode(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    /** OWNER 可维护带输入输出 Schema 和超时的命令定义，并通过同一接口读取完整 Schema。 */
    @Test
    void ownerManagesCommandDefinitions() throws Exception {
        UUID projectId = createProject(owner, "命令项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "actuator");
        String path = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/commands";
        MvcResult created = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commandKey":"reboot","name":"重启设备",\
                                 "description":"远程重启设备","timeoutSeconds":30,"sortOrder":0,\
                                 "inputSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"delay\\":\
                                 {\\"type\\":\\"integer\\"}}}",\
                                 "outputSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"result\\":\
                                 {\\"type\\":\\"string\\"}}}"}
                                """))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode createdBody = JSON.readTree(created.getResponse().getContentAsString());
        assertThat(createdBody.get("commandKey").asString()).isEqualTo("reboot");
        assertThat(createdBody.get("timeoutSeconds").asInt()).isEqualTo(30);
        assertThat(createdBody.get("inputSchema")).isNotNull();
        assertThat(createdBody.get("outputSchema")).isNotNull();
        UUID commandId = UUID.fromString(createdBody.get("id").asString());

        JsonNode listed = JSON.readTree(mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("inputSchema").asString()).contains("delay");

        MvcResult updated = mockMvc.perform(put(path + "/" + commandId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commandKey":"reboot","name":"重启设备",\
                                 "description":"远程重启设备（已修改）","timeoutSeconds":60,"sortOrder":1,\
                                 "inputSchema":"{\\"type\\":\\"object\\"}",\
                                 "outputSchema":"{\\"type\\":\\"object\\"}"}
                                """))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode updatedBody = JSON.readTree(updated.getResponse().getContentAsString());
        assertThat(updatedBody.get("description").asString()).contains("已修改");
        assertThat(updatedBody.get("timeoutSeconds").asInt()).isEqualTo(60);

        assertThat(mockMvc.perform(delete(path + "/" + commandId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(JSON.readTree(mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString()).isEmpty()).isTrue();
    }

    /** VIEWER 的写操作由服务端拒绝。 */
    @Test
    void viewerCannotWriteCommandDefinitions() throws Exception {
        UUID projectId = createProject(owner, "命令只读项目");
        Login ownerScoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(ownerScoped, projectId, "valve");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        String path = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/commands";
        MvcResult created = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commandKey":"open","name":"开阀","timeoutSeconds":10,"sortOrder":0}
                                """))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(created)).isEqualTo(30004);
    }

    /** OWNER 可发布草稿设备类型，发布后物模型全部子资源冻结不可变更。 */
    @Test
    void ownerPublishesDeviceTypeAndFreezesThingModel() throws Exception {
        UUID projectId = createProject(owner, "发布项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "freeze_test");
        String propPath = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/properties";
        String eventPath = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/events";
        String cmdPath = "/api/v1/projects/" + projectId + "/device-types/" + typeId + "/commands";

        // 发布前正常创建各子资源
        MvcResult propCreated = mockMvc.perform(post(propPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyKey\":\"temp\",\"name\":\"温度\",\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\",\"sortOrder\":0}"))
                .andReturn();
        assertThat(propCreated.getResponse().getStatus()).isEqualTo(201);
        UUID propId = UUID.fromString(JSON.readTree(propCreated.getResponse().getContentAsString()).get("id").asString());
        assertThat(mockMvc.perform(post(eventPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventKey\":\"alarm\",\"name\":\"告警\",\"level\":\"WARNING\",\"sortOrder\":0,\"parameters\":[]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);

        // 发布
        MvcResult published = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(published.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(published.getResponse().getContentAsString()).get("status").asString())
                .isEqualTo("PUBLISHED");

        // 发布后属性不可新增
        assertThat(errorCode(mockMvc.perform(post(propPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyKey\":\"humidity\",\"name\":\"湿度\",\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\",\"sortOrder\":1}"))
                .andReturn())).isEqualTo(30005);

        // 发布后已有属性不可修改
        assertThat(errorCode(mockMvc.perform(put(propPath + "/" + propId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyKey\":\"temp\",\"name\":\"新温度\",\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\",\"sortOrder\":0}"))
                .andReturn())).isEqualTo(30005);

        // 发布后事件不可新增
        assertThat(errorCode(mockMvc.perform(post(eventPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventKey\":\"fault\",\"name\":\"故障\",\"level\":\"ERROR\",\"sortOrder\":1,\"parameters\":[]}"))
                .andReturn())).isEqualTo(30005);

        // 发布后命令不可新增
        assertThat(errorCode(mockMvc.perform(post(cmdPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"reboot\",\"name\":\"重启\",\"timeoutSeconds\":30,\"sortOrder\":0}"))
                .andReturn())).isEqualTo(30005);

        // 发布后设备类型本身不可删除
        assertThat(errorCode(mockMvc.perform(delete("/api/v1/projects/" + projectId + "/device-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn())).isEqualTo(30005);

        // 发布后设备类型本身不可修改
        MvcResult typeUpdate = mockMvc.perform(put("/api/v1/projects/" + projectId + "/device-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\":\"freeze_test\",\"name\":\"新名称\",\"deviceKind\":\"DIRECT\",\"payloadProtocol\":\"STANDARD\",\"networkType\":\"WIFI\"}"))
                .andReturn();
        assertThat(errorCode(typeUpdate)).isEqualTo(30005);

        // 发布后不可重复发布
        MvcResult republish = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(errorCode(republish)).isEqualTo(30005);

        // 列表仍可正常读取
        assertThat(listTypes(scoped, projectId)).hasSize(1);
    }

    /** 发布必须等待已锁定同一聚合根的物模型写事务，不能越过草稿检查形成发布后写入。 */
    @Test
    void publishWaitsForConcurrentThingModelWrite() throws Exception {
        UUID projectId = createProject(owner, "并发冻结项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createdTypeId(scoped, projectId, "concurrent_freeze");
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            setProjectScope(writer, projectId);
            try (PreparedStatement lock = writer.prepareStatement(
                    "SELECT status FROM dev_type WHERE project_id = ? AND id = ? FOR UPDATE")) {
                lock.setObject(1, projectId); lock.setObject(2, typeId);
                assertThat(lock.executeQuery().next()).isTrue();
            }

            Future<Integer> publisher = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    setProjectScope(connection, projectId);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE dev_type SET status = 'PUBLISHED' WHERE project_id = ? AND id = ? AND status = 'DRAFT'")) {
                        statement.setObject(1, projectId); statement.setObject(2, typeId);
                        int updated = statement.executeUpdate();
                        connection.commit();
                        return updated;
                    }
                }
            });

            // 发布事务此时必须阻塞在同一 dev_type 行锁；若提前完成，冻结边界仍存在竞态。
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> publisher.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            try (PreparedStatement insert = writer.prepareStatement("""
                    INSERT INTO dev_property_definition
                        (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type)
                    VALUES (?, ?, ?, ?, 'locked_write', '锁内写入', 'REPORT', 'NUMBER')
                    """)) {
                insert.setObject(1, Uuid7.generate()); insert.setObject(2, tenantId);
                insert.setObject(3, projectId); insert.setObject(4, typeId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            writer.commit();
            assertThat(publisher.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** @param connection 当前事务连接 @param projectId RLS 项目范围 */
    private static void setProjectScope(Connection connection, UUID projectId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.project_id', ?, true)")) {
            statement.setString(1, projectId.toString()); statement.execute();
        }
    }

    /** 登录态所需的两种凭据。 @param accessToken 访问令牌 @param refreshToken 刷新令牌值 */
    private record Login(String accessToken, String refreshToken) { }
}
