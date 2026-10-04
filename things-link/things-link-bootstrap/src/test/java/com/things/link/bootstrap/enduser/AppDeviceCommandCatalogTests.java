package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ADR0110：完整App安全链、普通PG角色及有界目录；超限历史数据不能变成成功空目录。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class AppDeviceCommandCatalogTests {
    /** 专用数据库不与其他Bootstrap命令或调度夹具争用、清理。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_command_catalog").withUsername("thingslink").withPassword("thingslink");
    /** 真实App登录限流和安全依赖，随机端口不访问开发实例。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 测试使用实际HTTP JSON，不替代生产DTO。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 本地测试密码不会进入生产配置或目录响应。 */ private static final String PASSWORD = "catalog-test-password";
    /** 真实完整Servlet过滤链。 */ @Autowired private MockMvc mvc;
    /** 真实密码散列器使登录签发路径不被mock。 */ @Autowired private PasswordEncoder passwords;
    /** 普通角色业务连接用于证明非owner。 */ @Autowired private JdbcTemplate application;
    /** Owner仅用于播种坏历史数据和外部事实核对，不参与HTTP读取。 */ private JdbcTemplate owner;
    /** 每例独立身份，无需清理别的测试目录。 */ private UUID tenant;
    /** 当前项目与真实登录选择器。 */ private UUID project;
    /** 另一个项目的真实存在设备。 */ private UUID otherProject;
    /** 当前设备类型，目录限定于此类型。 */ private UUID type;
    /** 登录用户持有三种不同关系绑定。 */ private UUID user;
    /** 明确PRIMARY设备。 */ private UUID primary;
    /** 明确MEMBER设备。 */ private UUID member;
    /** 仅可读设备。 */ private UUID readOnly;
    /** 同项目但没有当前用户绑定。 */ private UUID unbound;
    /** 跨项目设备不能通过UUID猜测目录。 */ private UUID foreign;
    /** App登录项目短标识。 */ private String projectKey;
    /** 真实登录用户名称。 */ private String username;

    static { DATABASE.start(); REDIS.start(); }

    /** 隔离项目并保留原RLS与权限；只放宽正交速率避免快速夹具准备误命中429。 */
    @BeforeEach
    void seed() {
        TenantContext.clear();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("app_command_catalog");
        owner.update("UPDATE sys_quota_policy SET rest_api_read_rate_per_second=1000000,rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");
        tenant = UUID.randomUUID(); project = UUID.randomUUID(); otherProject = UUID.randomUUID(); user = UUID.randomUUID();
        projectKey = "pk" + project.toString().replace("-", "").substring(0, 20); username = "user" + user.toString().substring(0, 8);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenant, "目录测试");
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'目录项目','sh-1',?)", project, tenant, projectKey);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'邻项目','sh-1',?)", otherProject, tenant, "pk" + otherProject.toString().replace("-", "").substring(0, 20));
        type = seedType(project); UUID otherType = seedType(otherProject);
        primary = seedDevice(project, type); member = seedDevice(project, type); readOnly = seedDevice(project, type);
        unbound = seedDevice(project, type); foreign = seedDevice(otherProject, otherType);
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?,?,?,?,'ACTIVE')", user, tenant, username, passwords.encode(PASSWORD));
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'APP_ADMIN')", UUID.randomUUID(), tenant, project, user);
        bind(primary, "PRIMARY"); bind(member, "MEMBER"); bind(readOnly, "READ_ONLY");
    }

    /** 只清理线程范围，专库内各例唯一项目历史保留到容器销毁。 */
    @AfterEach
    void clearScope() { TenantContext.clear(); }

    /** PRIMARY/MEMBER读取同一真实类型目录，原文Schema和null字段保持精确闭集排序。 */
    @Test
    void primaryAndMemberReadOrderedNoStoreCatalog() throws Exception {
        addCommand("later", 2, null, null);
        addCommand("first", 1, "{\"type\":\"object\",\"additionalProperties\":false}", "{\"type\":\"object\"}");
        String token = login();
        for (UUID device : List.of(primary, member)) {
            JsonNode body = json(catalog(device, token), 200);
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("deviceId", "commands");
            assertThat(body.get("deviceId").stringValue()).isEqualTo(device.toString());
            assertThat(body.get("commands").size()).isEqualTo(2);
            JsonNode first = body.get("commands").get(0);
            assertThat(first.propertyNames()).containsExactlyInAnyOrder("commandKey", "name", "description", "inputSchema", "outputSchema", "timeoutSeconds");
            assertThat(first.get("commandKey").stringValue()).isEqualTo("first");
            assertThat(first.get("inputSchema").isString()).isTrue();
            assertThat(JSON.readTree(first.get("inputSchema").stringValue()).get("type").stringValue()).isEqualTo("object");
            assertThat(first.get("timeoutSeconds").intValue()).isEqualTo(30);
            assertThat(body.get("commands").get(1).get("inputSchema").isNull()).isTrue();
            assertThat(body.get("commands").get(1).get("description").isNull()).isTrue();
        }
    }

    /** READ_ONLY保持60011，未知、未绑定与跨项目均不泄漏目录。 */
    @Test
    void rejectsReadOnlyUnboundAndForeignDevices() throws Exception {
        addCommand("secret_command", 0, null, null); String token = login();
        error(catalog(readOnly, token), 403, 60011);
        for (UUID device : List.of(unbound, foreign, UUID.randomUUID())) error(catalog(device, token), 404, 60010);
    }

    /** 已签JWT不能复活被撤销项目角色，重验发生在目录读取之前。 */
    @Test
    void rechecksRemovedRoleForExistingToken() throws Exception {
        addCommand("restart", 0, null, null); String token = login();
        json(catalog(primary, token), 200);
        owner.update("DELETE FROM app_user_role WHERE project_id=? AND app_user_id=?", project, user);
        error(catalog(primary, token), 401, 60009);
    }

    /** 空目录本身合法，不把空值与超限无法完成的目录混淆。 */
    @Test
    void noDefinitionsReturnsExactEmptyCatalog() throws Exception {
        JsonNode result = json(catalog(primary, login()), 200);
        assertThat(result.get("commands").isArray()).isTrue(); assertThat(result.get("commands").isEmpty()).isTrue();
    }

    /** LIMIT101探测第101条，100条完整放行而不是返回前100条掩盖截断。 */
    @Test
    void rejectsHundredAndFirstDefinitionWithoutPartialItems() throws Exception {
        for (int index = 0; index < 100; index++) addCommand("command_" + index, index, null, null);
        String token = login(); assertThat(json(catalog(primary, token), 200).get("commands").size()).isEqualTo(100);
        addCommand("overflow", 100, null, null);
        error(catalog(primary, token), 503, 30064);
    }

    /** 坏历史Schema在SQL端预算检查；UTF8多字节不能按Java字符数放宽64KiB。 */
    @Test
    void rejectsOversizedIndividualInputAndOutputSchema() throws Exception {
        String oversized = JSON.writeValueAsString(Map.of("description", "测".repeat(23000)));
        assertThat(oversized.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65536);
        addCommand("bad_input", 0, oversized, null); String token = login(); error(catalog(primary, token), 503, 30064);
        owner.update("UPDATE dev_command_definition SET input_schema=NULL,output_schema=?::jsonb WHERE project_id=? AND device_type_id=?", oversized, project, type);
        error(catalog(primary, token), 503, 30064);
    }

    /** 每条都在64KiB内，但包装整体超过256KiB仍整体失败，不能截断字符串或项目。 */
    @Test
    void rejectsOversizedCombinedResponseWithoutTruncation() throws Exception {
        String schema = JSON.writeValueAsString(Map.of("description", "a".repeat(30000)));
        assertThat(schema.getBytes(StandardCharsets.UTF_8).length).isLessThan(65536);
        for (int index = 0; index < 5; index++) addCommand("large_" + index, index, schema, schema);
        error(catalog(primary, login()), 503, 30064);
    }

    /** 独立普通APP连接没有项目看不到目录，邻项目范围仍看不到当前项目定义。 */
    @Test
    void ordinaryAppDatabaseRoleCannotReadCatalogOutsideRlsProject() throws Exception {
        addCommand("private", 0, null, null);
        try (Connection connection = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink_app", "thingslink").getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM dev_command_definition WHERE project_id=?")) {
                query.setObject(1, project);
                try (ResultSet result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
                try (PreparedStatement scope = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true),set_config('app.project_id',?,true)")) {
                    scope.setString(1, tenant.toString()); scope.setString(2, otherProject.toString()); scope.execute();
                }
                try (ResultSet result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
            } finally { connection.rollback(); }
        }
        json(catalog(primary, login()), 200);
    }

    /** Owner仅播种产品类型，不为测试扩大生产列访问。 */
    private UUID seedType(UUID projectId) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,?,'控制类型','STANDARD','DIRECT','PUBLISHED')",
                id, tenant, projectId, "type_" + id.toString().replace("-", "")); return id;
    }

    /** 目录只依赖设备类型定义，不伪造命令成功或遥测状态。 */
    private UUID seedDevice(UUID projectId, UUID typeId) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?, ?,?,'控制设备','ONLINE')",
                id, tenant, projectId, typeId, "device_" + id.toString().replace("-", "")); return id;
    }

    /** 精确绑定关系分别覆盖控制与只读边界。 */
    private void bind(UUID device, String relation) {
        owner.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, project, user, device, relation);
    }

    /** 直接SQL允许构造旧库超大Schema，而不绕过被测HTTP业务读取。 */
    private void addCommand(String key, int sort, String input, String output) {
        owner.update("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,sort_order,input_schema,output_schema,timeout_seconds) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,30)",
                UUID.randomUUID(), tenant, project, type, key, "命令" + key, sort, input, output);
    }

    /** 使用真实登录签发，避免凭据夹具跳过App角色和项目选择。 */
    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/app/auth/login").with(request -> { request.setRemoteAddr(clientIp()); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("projectKey", projectKey, "username", username, "password", PASSWORD)))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsByteArray()).get("accessToken").stringValue();
    }

    /** 每例唯一来源保留真实限流，不借反复重置Redis绕过限额。 */
    private String clientIp() {
        String hex = project.toString().replace("-", "");
        return "2001:db8:" + String.join(":", List.of(hex.substring(0, 4), hex.substring(4, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20, 24)));
    }

    /** 实际目录通过真实JWT过滤链；不手工建立TenantContext。 */
    private MvcResult catalog(UUID device, String token) throws Exception {
        return mvc.perform(get("/api/v1/app/devices/{deviceId}/command-definitions", device).header("Authorization", "Bearer " + token)
                .with(request -> { request.setRemoteAddr(clientIp()); return request; })).andReturn();
    }

    /** HTTP状态、业务码和无部分成功字段同时钉住失败边界。 */
    private static void error(MvcResult response, int status, int code) throws Exception {
        JsonNode value = json(response, status); assertThat(value.get("code").intValue()).isEqualTo(code);
        assertThat(value.has("commands")).isFalse();
    }

    /** 正常与拒绝都明确禁止缓存，首因原响应用于定位而非模糊非200断言。 */
    private static JsonNode json(MvcResult response, int status) throws Exception {
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(response.getResponse().getHeader("Cache-Control")).contains("no-store");
        return JSON.readTree(response.getResponse().getContentAsByteArray());
    }

    /** 单独数据库/Redis，真实迁移owner和业务APP连接显式分开。 */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "thingslink_app"); registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl); registry.add("spring.flyway.user", DATABASE::getUsername); registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.outbox.publisher.enabled", () -> "false"); registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
