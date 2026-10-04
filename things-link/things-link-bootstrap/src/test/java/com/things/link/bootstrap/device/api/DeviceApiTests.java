package com.things.link.bootstrap.device.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 设备实例 API 端到端测试。 */
@AutoConfigureMockMvc
@DisplayName("设备接口（S3-1）")
class DeviceApiTests extends AbstractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthRateLimiter rateLimiter;
    /** 批量接口使用与PG事实匹配的真实Redis候选。 */
    @Autowired private DeviceCurrentValueCache currentValueCache;
    private Login owner, viewer;
    private UUID viewerAccountId;

    @BeforeEach void seed() throws Exception {
        rateLimiter.clear();
        // 命令是不可随设备级联删除的审计事实，测试清场必须按外键依赖显式逆序清理。
        jdbcTemplate.update("DELETE FROM ts_device_command_attempt");
        jdbcTemplate.update("DELETE FROM ts_device_command");
        jdbcTemplate.update("DELETE FROM sys_outbox_event");
        jdbcTemplate.update("DELETE FROM sys_project_member");
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

    /** 调试页下发命令复用正式权限：VIEWER 不能控制设备（§7.1「不新增权限点」）。 */
    @Test void viewerCannotSubmitCommand() throws Exception {
        UUID projectId = createProject(owner, "命令只读项目");
        Login ownerScoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(ownerScoped, projectId, "cmd_ro_01", "只读命令设备");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);

        var response = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices/" + deviceId + "/commands")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken())
                        .header("Idempotency-Key", "ax5d-viewer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"reboot\",\"input\":{}}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("VIEWER 不得下发命令").isIn(403, 404);
    }

    /** 接入诊断：项目成员可读，返回冻结字段集，且不含任何凭据字段（§7.1／§7.2）。 */
    @Test void memberReadsAccessDiagnosticsWithoutCredentials() throws Exception {
        UUID projectId = createProject(owner, "诊断项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(scoped, projectId, "diag_01", "诊断设备");

        var response = mockMvc.perform(get("/api/v1/projects/" + projectId + "/devices/" + deviceId
                        + "/access-diagnostics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("protocol").asString()).as("没有接入配置行的存量设备按 MQTT 默认档")
                .isEqualTo("MQTT");
        assertThat(body.get("state").asString()).as("无会话且无活动即离线").isEqualTo("OFFLINE");
        assertThat(body.has("generation")).isTrue();
        assertThat(body.has("enabled")).isTrue();
        assertThat(body.has("configVersion")).isTrue();
        // 只读调试面绝不能把凭据带出来：响应里不允许出现密钥/令牌/摘要语义的键名或值。
        assertThat(response.getContentAsString()).doesNotContain("secret").doesNotContain("Secret")
                .doesNotContain("authorization").doesNotContain("credential_hash");
    }

    /** 接入诊断与设备读权限同源，且不跨项目泄露：作用域在 A 项目的令牌读不到 B 项目的诊断（§7.1）。 */
    @Test void accessDiagnosticsDoNotCrossProjectBoundary() throws Exception {
        UUID ownProject = createProject(owner, "诊断自有项目");
        Login ownScoped = switchProject(owner, ownProject);

        // 另一个项目由 viewer 拥有：owner 的令牌即使有效，也不得读到它的诊断。
        UUID otherProject = createProject(viewer, "诊断他人项目");
        Login viewerScoped = switchProject(viewer, otherProject);
        UUID otherDeviceId = createDevice(viewerScoped, otherProject, "diag_02", "他人设备");

        var response = mockMvc.perform(get("/api/v1/projects/" + otherProject + "/devices/" + otherDeviceId
                        + "/access-diagnostics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownScoped.accessToken()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("跨项目必须被拒，而不是返回他人项目的诊断")
                .isIn(403, 404);
    }

    @Test void ownerCreatesAndListsDevices() throws Exception {
        UUID projectId = createProject(owner, "设备项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"sensor_01\",\"name\":\"温度传感器\",\"location\":\"机房A\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("deviceKey").asString()).isEqualTo("sensor_01");
        assertThat(body.get("status").asString()).isEqualTo("INACTIVE");

        JsonNode list = JSON.readTree(mockMvc.perform(get("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(list).hasSize(1);
    }

    @Test void ownerUpdatesAndDeletesDevice() throws Exception {
        UUID projectId = createProject(owner, "维护项目");
        Login scoped = switchProject(owner, projectId);
        UUID id = createDevice(scoped, projectId, "pump_01", "水泵");
        MvcResult updated = mockMvc.perform(put("/api/v1/projects/" + projectId + "/devices/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新水泵\",\"location\":\"泵房B\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(updated.getResponse().getContentAsString()).get("name").asString()).isEqualTo("新水泵");
        assertThat(mockMvc.perform(delete("/api/v1/projects/" + projectId + "/devices/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test void viewerCannotCreateOrDelete() throws Exception {
        UUID projectId = createProject(owner, "只读项目");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"secret_sensor\",\"name\":\"秘密传感器\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(created)).isEqualTo(30024);
    }

    /** 概要对所有项目成员可读，但不得通过项目 ID 泄露非成员项目。 */
    @Test
    void projectOverviewAllowsViewerAndHidesUnrelatedProject() throws Exception {
        UUID projectId = createProject(owner, "概要项目");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);

        MvcResult overview = mockMvc.perform(get("/api/v1/projects/" + projectId + "/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken()))
                .andReturn();
        assertThat(overview.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(overview.getResponse().getContentAsString());
        assertThat(body.get("devices").get("total").asLong()).isZero();
        assertThat(body.get("alarmRate").get("available").asBoolean()).isTrue();
        assertThat(body.get("alarmRate").get("value").asDouble()).isZero();

        Login unrelated = registerAndLogin("unrelated-overview@example.com");
        MvcResult hidden = mockMvc.perform(get("/api/v1/projects/" + projectId + "/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unrelated.accessToken()))
                .andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(hidden)).isEqualTo(50001);
    }

    /** 真实 PostgreSQL 验证高级筛选维度 AND、集合 OR 与 (createdAt,id) 稳定键集翻页。 */
    @Test
    void searchesDevicesByWhitelistedCompositeFiltersAndCursor() throws Exception {
        UUID projectId = createProject(owner, "设备筛选项目");
        Login scoped = switchProject(owner, projectId);
        UUID typeId = createDeviceType(scoped, projectId, "search_type", "筛选类型");
        UUID alternateTypeId = createDeviceType(scoped, projectId, "search_alt_type", "候选类型");
        UUID firstDevice = createTypedDevice(scoped, projectId, typeId, "search_a", "上海温度设备甲");
        UUID secondDevice = createTypedDevice(scoped, projectId, typeId, "search_b", "上海温度设备乙");
        UUID ignoredDevice = createTypedDevice(scoped, projectId, typeId, "search_c", "北京温度设备");

        String groupsPath = "/api/v1/projects/" + projectId + "/device-groups";
        MvcResult groupResult = mockMvc.perform(post(groupsPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"待筛选设备\",\"type\":\"STATIC\"}"))
                .andReturn();
        UUID groupId = UUID.fromString(JSON.readTree(groupResult.getResponse().getContentAsString())
                .get("id").asString());
        mockMvc.perform(put(groupsPath + "/" + groupId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceIds\":[\"%s\",\"%s\",\"%s\"]}"
                                .formatted(firstDevice, secondDevice, ignoredDevice)))
                .andReturn();
        for (UUID deviceId : java.util.List.of(firstDevice, secondDevice)) {
            mockMvc.perform(put("/api/v1/projects/%s/devices/%s/tags".formatted(projectId, deviceId))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"site\",\"value\":\"shanghai\"}"))
                    .andReturn();
        }

        String searchPath = "/api/v1/projects/" + projectId + "/devices/search";
        MvcResult first = mockMvc.perform(get(searchPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("keyword", "上海")
                        .queryParam("deviceTypeIds", typeId.toString(), alternateTypeId.toString())
                        .queryParam("statuses", "INACTIVE", "OFFLINE")
                        .queryParam("groupId", groupId.toString())
                        .queryParam("tagKey", "site")
                        .queryParam("tagValue", "shanghai")
                        .queryParam("limit", "1"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstPage = JSON.readTree(first.getResponse().getContentAsString());
        assertThat(firstPage.get("items")).hasSize(1);
        assertThat(firstPage.get("hasMore").asBoolean()).isTrue();

        MvcResult second = mockMvc.perform(get(searchPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("keyword", "上海")
                        .queryParam("deviceTypeIds", typeId.toString(), alternateTypeId.toString())
                        .queryParam("statuses", "INACTIVE", "OFFLINE")
                        .queryParam("groupId", groupId.toString())
                        .queryParam("tagKey", "site")
                        .queryParam("tagValue", "shanghai")
                        .queryParam("limit", "1")
                        .queryParam("cursor", firstPage.get("nextCursor").asString()))
                .andReturn();
        JsonNode secondPage = JSON.readTree(second.getResponse().getContentAsString());
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("hasMore").asBoolean()).isFalse();
        assertThat(java.util.Set.of(
                firstPage.get("items").get(0).get("id").asString(),
                secondPage.get("items").get(0).get("id").asString()))
                .containsExactlyInAnyOrder(firstDevice.toString(), secondDevice.toString());

        MvcResult dynamicGroupResult = mockMvc.perform(post(groupsPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"上海动态组\",\"type\":\"DYNAMIC\","
                                + "\"rule\":{\"deviceTypeIds\":[\"%s\"],\"statuses\":[\"INACTIVE\"],"
                                + "\"tags\":{\"site\":\"shanghai\"},\"tagMatch\":\"ALL\"}}")
                                .formatted(typeId)))
                .andReturn();
        UUID dynamicGroupId = UUID.fromString(JSON.readTree(dynamicGroupResult.getResponse().getContentAsString())
                .get("id").asString());
        JsonNode dynamicPage = JSON.readTree(mockMvc.perform(get(searchPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("keyword", "上海")
                        .queryParam("groupId", dynamicGroupId.toString()))
                .andReturn().getResponse().getContentAsString());
        assertThat(dynamicPage.get("items")).hasSize(2);

        MvcResult incompleteTag = mockMvc.perform(get(searchPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("tagKey", "site"))
                .andReturn();
        assertThat(errorCode(incompleteTag)).isEqualTo(10001);
        MvcResult forgedCursor = mockMvc.perform(get(searchPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("cursor", "not-a-valid-cursor"))
                .andReturn();
        assertThat(errorCode(forgedCursor)).isEqualTo(10001);
    }

    /** 真实 PostgreSQL 验证静态成员、键值标签和动态规则即时求值组成同一项目闭环。 */
    @Test void ownerManagesStaticAndDynamicDeviceGroups() throws Exception {
        UUID projectId = createProject(owner, "设备组项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(scoped, projectId, "group_sensor", "分组传感器");
        String groupsPath = "/api/v1/projects/" + projectId + "/device-groups";

        MvcResult staticCreated = mockMvc.perform(post(groupsPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"机房设备\",\"type\":\"STATIC\"}"))
                .andReturn();
        assertThat(staticCreated.getResponse().getStatus()).isEqualTo(201);
        UUID staticId = UUID.fromString(JSON.readTree(staticCreated.getResponse().getContentAsString())
                .get("id").asString());
        assertThat(mockMvc.perform(put(groupsPath + "/" + staticId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceIds\":[\"%s\",\"%s\"]}".formatted(deviceId, deviceId)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        JsonNode staticMembers = JSON.readTree(mockMvc.perform(get(groupsPath + "/" + staticId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn().getResponse().getContentAsString());
        assertThat(staticMembers).hasSize(1);

        String tagsPath = "/api/v1/projects/%s/devices/%s/tags".formatted(projectId, deviceId);
        assertThat(mockMvc.perform(put(tagsPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"site\",\"value\":\"shanghai\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        MvcResult dynamicCreated = mockMvc.perform(post(groupsPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"上海设备\",\"type\":\"DYNAMIC\","
                                + "\"rule\":{\"statuses\":[\"INACTIVE\"],\"tags\":{\"site\":\"shanghai\"},"
                                + "\"tagMatch\":\"ALL\"}}"))
                .andReturn();
        assertThat(dynamicCreated.getResponse().getStatus()).isEqualTo(201);
        UUID dynamicId = UUID.fromString(JSON.readTree(dynamicCreated.getResponse().getContentAsString())
                .get("id").asString());
        assertThat(JSON.readTree(mockMvc.perform(get(groupsPath + "/" + dynamicId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString())).hasSize(1);

        mockMvc.perform(delete(tagsPath + "/site")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(JSON.readTree(mockMvc.perform(get(groupsPath + "/" + dynamicId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString())).isEmpty();
    }

    /** VIEWER 只能读组，跨项目组 ID 统一返回不存在，空动态规则使用冻结业务码。 */
    @Test void deviceGroupsValidateRulesAndHideCrossProjectResources() throws Exception {
        UUID projectA = createProject(owner, "设备组甲项目");
        Login ownerA = switchProject(owner, projectA);
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectA + "/device-groups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"甲组\",\"type\":\"STATIC\"}"))
                .andReturn();
        UUID groupId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asString());

        addMember(projectA, viewerAccountId, "VIEWER");
        Login viewerA = switchProject(viewer, projectA);
        MvcResult forbidden = mockMvc.perform(post("/api/v1/projects/" + projectA + "/device-groups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权组\",\"type\":\"STATIC\"}"))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(forbidden)).isEqualTo(30024);

        MvcResult invalid = mockMvc.perform(post("/api/v1/projects/" + projectA + "/device-groups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"空规则\",\"type\":\"DYNAMIC\",\"rule\":{}}"))
                .andReturn();
        assertThat(errorCode(invalid)).isEqualTo(30034);

        Login other = registerAndLogin("other-device-group@example.com");
        UUID projectB = createProject(other, "设备组乙项目");
        Login ownerB = switchProject(other, projectB);
        MvcResult hidden = mockMvc.perform(get("/api/v1/projects/%s/device-groups/%s/devices"
                        .formatted(projectB, groupId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.accessToken()))
                .andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(hidden)).isEqualTo(30032);
    }

    @Test void deviceKeyMustMatchMqttTopicCharset() throws Exception {
        UUID projectId = createProject(owner, "校验项目");
        Login scoped = switchProject(owner, projectId);
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"sensor/01\",\"name\":\"非法设备\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo(10001);
    }

    @Test void ownerGeneratesAndRevokesCredential() throws Exception {
        UUID projectId = createProject(owner, "凭据项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(scoped, projectId, "secure_01", "安全设备");
        String credPath = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/credentials";

        // 生成凭据，响应含明文密钥
        MvcResult gen = mockMvc.perform(post(credPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(gen.getResponse().getStatus()).isEqualTo(201);
        JsonNode genBody = JSON.readTree(gen.getResponse().getContentAsString());
        assertThat(genBody.get("plainSecret").asString()).hasSize(64);

        // 列表不含明文
        JsonNode list = JSON.readTree(mockMvc.perform(get(credPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).has("plainSecret")).isFalse();

        // 作废后再查列表为空
        UUID credId = UUID.fromString(list.get(0).get("id").asString());
        assertThat(mockMvc.perform(delete(credPath + "/" + credId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(JSON.readTree(mockMvc.perform(get(credPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString()).isEmpty()).isTrue();
    }

    @Test void ownerReadsAndUpdatesShadow() throws Exception {
        UUID projectId = createProject(owner, "影子项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(scoped, projectId, "shadow_01", "影子设备");
        String shadowPath = "/api/v1/projects/" + projectId + "/devices/" + deviceId + "/shadow";

        // 首次读取自动创建空影子
        JsonNode shadow = JSON.readTree(mockMvc.perform(get(shadowPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(shadow.get("version").asInt()).isZero();

        // 更新 desired
        MvcResult updated = mockMvc.perform(put(shadowPath + "/desired")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desired\":\"{\\\"temp\\\":25}\",\"version\":0}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode updatedBody = JSON.readTree(updated.getResponse().getContentAsString());
        assertThat(updatedBody.get("desired").asString()).contains("temp");
        assertThat(updatedBody.get("version").asInt()).isEqualTo(1);

        // 版本冲突
        MvcResult conflict = mockMvc.perform(put(shadowPath + "/desired")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desired\":\"{}\",\"version\":0}"))
                .andReturn();
        assertThat(errorCode(conflict)).isEqualTo(30023);
    }

    /** 批量当前值按请求属性返回 PostgreSQL 事实值，并保持 JSON 基础类型。 */
    @Test void ownerQueriesBatchCurrentValues() throws Exception {
        UUID projectId = createProject(owner, "当前值项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createCommandFixture(scoped, projectId, "current_01").deviceId();
        UUID sourceVersion;
        UUID tenantId;
        try (var connection = fixtureOwnerConnection(); var statement = connection.prepareStatement(
                "SELECT tenant_id, thing_model_version_id FROM dev_device WHERE id = ?")) {
            statement.setObject(1, deviceId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                tenantId = rows.getObject("tenant_id", UUID.class);
                sourceVersion = rows.getObject("thing_model_version_id", UUID.class);
            }
        }
        String revision = "9007199254740993";
        java.time.Instant occurredAt = java.time.Instant.parse("2026-08-08T05:00:00.000123Z");
        // 此处只验证HTTP投影；真实CAS接受与来源写入由独立摄入集成反例验证。
        TenantContext.set(
                new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_shadow
                        (device_id, tenant_id, project_id, reported, reported_at, version,
                         reported_sequence, reported_revisions, reported_model_version)
                    SELECT id, tenant_id, project_id, '{"temperature":26.5,"online":true}'::jsonb,
                           '{"temperature":"2026-08-08T05:00:00.000123Z"}'::jsonb, 2,
                           9007199254740993, '{"temperature":"9007199254740993"}'::jsonb,
                           jsonb_build_object('temperature', thing_model_version_id::text)
                      FROM dev_device WHERE id = ?
                    ON CONFLICT (device_id) DO UPDATE SET reported = EXCLUDED.reported,
                        reported_at = EXCLUDED.reported_at, version = EXCLUDED.version,
                        reported_sequence = EXCLUDED.reported_sequence,
                        reported_revisions = EXCLUDED.reported_revisions,
                        reported_model_version = EXCLUDED.reported_model_version
                    """, deviceId);
        } finally {
            TenantContext.clear();
        }
        currentValueCache.merge(projectId, deviceId, java.util.Map.of("temperature",
                new DeviceCurrentValueCache.ReportedValue("26.5", occurredAt, 2, revision, sourceVersion)));

        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId
                        + "/devices/current-values/query")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceIds\":[\"%s\"],\"propertyKeys\":[\"temperature\"]}"
                                .formatted(deviceId))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode item = JSON.readTree(result.getResponse().getContentAsString()).get("items").get(0);
        assertThat(item.get("values").get("temperature").asDouble()).isEqualTo(26.5);
        assertThat(item.get("values").has("online")).isFalse();
        assertThat(item.get("occurredAt").get("temperature").asString())
                .isEqualTo("2026-08-08T05:00:00.000123Z");
        assertThat(item.get("reportedRevisions").get("temperature").isString()).isTrue();
        assertThat(item.get("reportedRevisions").get("temperature").asString()).isEqualTo(revision);
        assertThat(item.get("reportedRevisions").has("online")).isFalse();
        assertThat(item.get("thingModelVersionIds").get("temperature").asString())
                .isEqualTo(sourceVersion.toString());
        assertThat(item.get("thingModelVersionIds").has("online")).isFalse();
    }

    @Test void ownerListsConnections() throws Exception {
        UUID projectId = createProject(owner, "连接项目");
        Login scoped = switchProject(owner, projectId);
        UUID deviceId = createDevice(scoped, projectId, "conn_01", "连接设备");
        JsonNode list = JSON.readTree(mockMvc.perform(get("/api/v1/projects/" + projectId
                        + "/devices/" + deviceId + "/connections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(list).isEmpty();
    }

    /** 命令 API 以 202 可靠受理，同一 Idempotency-Key 返回原 commandId，查询可见首次 attempt。 */
    @Test void ownerSubmitsIdempotentDeviceCommand() throws Exception {
        UUID projectId = createProject(owner, "命令项目");
        Login scoped = switchProject(owner, projectId);
        CommandFixture fixture = createCommandFixture(scoped, projectId, "command_sensor");
        String path = "/api/v1/projects/%s/devices/%s/commands".formatted(projectId, fixture.deviceId());
        String payload = "{\"commandKey\":\"restart\",\"input\":{\"delay\":3}}";

        MvcResult first = mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                .header("Idempotency-Key", "restart-once").contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        JsonNode firstBody = JSON.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(firstBody.get("attemptCount").asInt()).isEqualTo(1);

        MvcResult replay = mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                .header("Idempotency-Key", "restart-once").contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
        assertThat(JSON.readTree(replay.getResponse().getContentAsString()).get("id"))
                .isEqualTo(firstBody.get("id"));

        JsonNode queried = JSON.readTree(mockMvc.perform(get(path + "/" + firstBody.get("id").asString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(queried.get("attempts")).hasSize(1);
        assertThat(queried.get("attempts").get(0).get("status").asString()).isEqualTo("PENDING");
    }

    /** 命令输入必须经过已发布物模型 Schema，VIEWER 即使知道设备 ID 也不能控制。 */
    @Test void commandValidatesSchemaAndControlPermission() throws Exception {
        UUID projectId = createProject(owner, "命令权限项目");
        Login scoped = switchProject(owner, projectId);
        CommandFixture fixture = createCommandFixture(scoped, projectId, "secured_command_sensor");
        String path = "/api/v1/projects/%s/devices/%s/commands".formatted(projectId, fixture.deviceId());

        MvcResult invalid = mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                .header("Idempotency-Key", "invalid-command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"input\":{\"delay\":\"fast\"}}"))
                .andReturn();
        assertThat(errorCode(invalid)).isEqualTo(30030);

        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        MvcResult forbidden = mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken())
                .header("Idempotency-Key", "viewer-command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"input\":{\"delay\":3}}"))
                .andReturn();
        assertThat(errorCode(forbidden)).isEqualTo(30029);
    }

    // --- helpers ---
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(v -> v.startsWith("tc_refresh=")).map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    private UUID createProject(Login login, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name))).andReturn();
        return UUID.fromString(JSON.readTree(r.getResponse().getContentAsString()).get("id").asString());
    }

    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/switch-project").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        return new Login(JSON.readTree(r.getResponse().getContentAsString()).get("accessToken").asString(), login.refreshToken());
    }

    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?,?,?,?)",
                Uuid7.generate(), projectId, accountId, role);
    }

    private UUID accountId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
    }

    private UUID createDevice(Login login, UUID projectId, String key, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceKey\":\"%s\",\"name\":\"%s\"}".formatted(key, name))).andReturn();
        return UUID.fromString(JSON.readTree(r.getResponse().getContentAsString()).get("id").asString());
    }

    /** 通过真实 API 创建组合筛选夹具使用的设备类型。 */
    private UUID createDeviceType(Login login, UUID projectId, String key, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\":\"%s\",\"name\":\"%s\",\"deviceKind\":\"DIRECT\","
                                .concat("\"payloadProtocol\":\"STANDARD\",\"networkType\":\"WIFI\"}")
                                .formatted(key, name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 通过真实 API 创建绑定指定类型的设备。 */
    private UUID createTypedDevice(Login login, UUID projectId, UUID typeId, String key, String name)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"%s\",\"name\":\"%s\"}"
                                .formatted(typeId, key, name)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 通过真实 API 建立发布命令定义与绑定设备，禁止测试绕过聚合发布规则直写 dev_*。 */
    private CommandFixture createCommandFixture(Login login, UUID projectId, String deviceKey) throws Exception {
        MvcResult typeResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeKey\":\"%s\",\"name\":\"命令设备\",\"deviceKind\":\"DIRECT\","
                        .concat("\"payloadProtocol\":\"STANDARD\",\"networkType\":\"WIFI\"}")
                        .formatted(deviceKey))).andReturn();
        UUID typeId = UUID.fromString(JSON.readTree(typeResult.getResponse().getContentAsString()).get("id").asString());
        mockMvc.perform(post("/api/v1/projects/%s/device-types/%s/commands".formatted(projectId, typeId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"name\":\"重启\","
                        + "\"inputSchema\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"delay\\\":{\\\"type\\\":\\\"integer\\\"}},\\\"required\\\":[\\\"delay\\\"],\\\"additionalProperties\\\":false}\","
                        + "\"outputSchema\":\"{\\\"type\\\":\\\"object\\\"}\",\"timeoutSeconds\":60,\"sortOrder\":0}"))
                .andReturn();
        mockMvc.perform(post("/api/v1/projects/%s/device-types/%s/publish".formatted(projectId, typeId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())).andReturn();
        MvcResult device = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"%s\",\"name\":\"命令设备\"}"
                        .formatted(typeId, deviceKey))).andReturn();
        return new CommandFixture(typeId,
                UUID.fromString(JSON.readTree(device.getResponse().getContentAsString()).get("id").asString()));
    }

    private int errorCode(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).get("code").asInt();
    }

    /**
     * OTA固件草稿必须绑定物模型版本身份，而设备类型详情不携带该身份。
     *
     * <p>本接口是控制台取得该身份的唯一读取入口，因此必须证明三件事：已发布类型返回真实的
     * 不可变版本（含摘要与发布时间）、未发布类型以30052 fail-closed、跨项目路径读不到其他项目的版本。
     * 读取本身不创建版本，也不把设备类型的状态改成已发布。
     */
    @Test void readsLatestThingModelVersionOnlyInsideItsOwnProject() throws Exception {
        UUID projectId = createProject(owner, "物模型版本读取");
        Login scoped = switchProject(owner, projectId);
        UUID draftType = createDeviceType(scoped, projectId, "draft_version_type", "未发布类型");

        MvcResult unpublished = mockMvc.perform(get(
                        "/api/v1/projects/%s/device-types/%s/thing-model-versions/latest".formatted(projectId, draftType))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn();
        assertThat(unpublished.getResponse().getStatus())
                .as("没有已发布版本时不能凭空返回一个版本身份")
                .isEqualTo(404);
        assertThat(errorCode(unpublished)).isEqualTo(30052);

        CommandFixture fixture = createCommandFixture(scoped, projectId, "model_version_device");
        MvcResult latest = mockMvc.perform(get(
                        "/api/v1/projects/%s/device-types/%s/thing-model-versions/latest"
                                .formatted(projectId, fixture.deviceTypeId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn();
        assertThat(latest.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(latest.getResponse().getContentAsString());
        assertThat(body.get("deviceTypeId").asString()).isEqualTo(fixture.deviceTypeId().toString());
        assertThat(body.get("versionNumber").asString()).matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
        assertThat(body.get("changeLevel").asString()).isIn("PATCH", "MINOR", "MAJOR");
        assertThat(body.get("schemaDigest").asString()).matches("[0-9a-f]{64}");
        assertThat(body.get("digestAlgorithm").asString()).isNotBlank();
        assertThat(body.get("publishedAt").asString()).isNotBlank();
        assertThat(body.has("modelSnapshot"))
                .as("只投影版本身份与摘要，不下发完整模型快照")
                .isFalse();

        Login other = registerAndLogin("other-ota-model-version@example.com");
        UUID otherProject = createProject(other, "另一个项目");
        Login otherScoped = switchProject(other, otherProject);
        MvcResult crossProject = mockMvc.perform(get(
                        "/api/v1/projects/%s/device-types/%s/thing-model-versions/latest"
                                .formatted(otherProject, fixture.deviceTypeId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherScoped.accessToken())).andReturn();
        assertThat(crossProject.getResponse().getStatus())
                .as("版本读取必须按路径项目收窄，不能读到其他项目的版本")
                .isEqualTo(404);
        assertThat(errorCode(crossProject)).isEqualTo(30052);
    }

    private record Login(String accessToken, String refreshToken) { }
    /** @param deviceTypeId 已发布类型 @param deviceId 绑定设备 */
    private record CommandFixture(UUID deviceTypeId, UUID deviceId) { }
}
