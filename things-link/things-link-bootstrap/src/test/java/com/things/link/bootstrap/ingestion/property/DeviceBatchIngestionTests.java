package com.things.link.bootstrap.ingestion.property;

import com.things.link.device.application.DeviceBatchIngestionService;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.ResolvedSubDeviceReport;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.SubDeviceReport;
import com.things.link.shared.tenant.TenantContext;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 网关批量属性上报归属复核端到端测试（S10-2b）。
 *
 * <p>直接调用 {@link DeviceBatchIngestionService} 复核（Kafka 消费者只做信封校验与发布编排），
 * 验证按 {@code dev_topo} 的「子设备是否绑定到上报网关」逐条目判定与跨项目隔离。</p>
 */
@AutoConfigureMockMvc
@DisplayName("网关批量属性上报归属复核（S10-2b）")
class DeviceBatchIngestionTests extends AbstractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthRateLimiter rateLimiter;
    @Autowired private DeviceBatchIngestionService batchService;
    @Autowired private DeviceTopologyIngestionService topologyService;
    /** 测试夹具扩容走与生产相同的 CAS 绑定与提交后缓存失效，不改任何生产守卫。 */
    @Autowired private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    private Login owner;

    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        jdbcTemplate.update("DELETE FROM ts_device_command_attempt");
        jdbcTemplate.update("DELETE FROM ts_device_command");
        jdbcTemplate.update("DELETE FROM sys_outbox_event");
        jdbcTemplate.update("DELETE FROM sys_inbox_message");
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
        owner = registerAndLogin("owner-batch2@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 只有绑定到上报网关的条目通过复核；跨网关/未绑定/不存在分别拒绝。 */
    @Test
    void resolvesOnlySubDevicesBoundToReportingGateway() throws Exception {
        UUID projectId = createProject(owner, "批量复核项目");
        Login scoped = switchProject(owner, projectId);
        // 用例需要 5 台设备（2 网关 + 3 子设备）才能同时覆盖「同网关通过、跨网关拒绝、
        // 未绑定拒绝、不存在拒绝」四种归属，超过新租户默认的 PLAN_R1_FREE 冻结上限 3 台。
        useStandardPlan(tenantOf(projectId));
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID otherGateway = createGatewayDevice(scoped, projectId, "gw_02");
        UUID bound = createSubDevice(scoped, projectId, "sub_bound");
        UUID stolen = createSubDevice(scoped, projectId, "sub_stolen");
        UUID unbound = createSubDevice(scoped, projectId, "sub_unbound");

        bind(projectId, gatewayId, "sub_bound");
        bind(projectId, otherGateway, "sub_stolen");

        GatewayBatchMessage batch = batch(projectId, gatewayId, List.of(
                report("sub_bound"), report("sub_stolen"), report("sub_unbound"), report("sub_missing")));

        List<ResolvedSubDeviceReport> resolved = batchService.resolve(batch);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).deviceId()).isEqualTo(bound);
    }

    /** 网关不得代报其他项目的子设备；RLS 使跨项目子设备按不存在处理。 */
    @Test
    void rejectsCrossProjectSubDevice() throws Exception {
        UUID projectA = createProject(owner, "批量项目A");
        // 跨项目归属复核需要同一租户下的**两个**项目，超过新租户默认 PLAN_R1_FREE 的
        // projects_max = 1；绑定现有STANDARD模板，保留生产项目数守卫。
        useStandardPlan(tenantOf(projectA));
        UUID projectB = createProject(owner, "批量项目B");
        Login scopedA = switchProject(owner, projectA);
        Login scopedB = switchProject(scopedA, projectB);
        UUID gatewayA = createGatewayDevice(scopedA, projectA, "gw_a");
        UUID subB = createSubDevice(scopedB, projectB, "sub_in_b");

        GatewayBatchMessage batch = batch(projectA, gatewayA, List.of(report("sub_in_b")));

        assertThat(batchService.resolve(batch)).isEmpty();
        assertThat(subB).isNotNull();
    }

    // --- helpers ---

    private void bind(UUID projectId, UUID gatewayId, String subDeviceKey) {
        topologyService.ingest(new DeviceTopologyMessage(Uuid7.generate(), tenantOf(projectId), projectId,
                gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, subDeviceKey, null, null, Instant.now(), "trace"));
    }

    private GatewayBatchMessage batch(UUID projectId, UUID gatewayId, List<SubDeviceReport> reports) {
        int frameBytes = 100;
        List<GatewayBatchMessage.Entry> entries = reports.stream()
                .map(report -> (GatewayBatchMessage.Entry) new GatewayBatchMessage.Valid(
                        report, frameBytes / reports.size()))
                .toList();
        return new GatewayBatchMessage(tenantOf(projectId), projectId, gatewayId, frameBytes,
                Instant.now(), "trace-batch", entries);
    }

    private SubDeviceReport report(String deviceKey) {
        return new SubDeviceReport(Uuid7.generate(), deviceKey, Instant.now(), "1.0.0",
                Map.of("temperature", 23.5));
    }

    private UUID tenantOf(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
    }

    /** 绑定现有STANDARD冻结模板，满足双项目及五设备夹具，保留生产配额守卫。 */
    private void useStandardPlan(UUID tenantId) {
        UUID standardPolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        quotaPolicyAssignmentService.assign(tenantId, standardPolicyId, assignmentVersion);
    }

    private UUID createGatewayDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createType(login, projectId, key + "_type", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
        return createTypedDevice(login, projectId, typeId, key, "网关");
    }

    private UUID createSubDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createType(login, projectId, key + "_type", "SUB_DEVICE", "STANDARD", "ZIGBEE");
        return createTypedDevice(login, projectId, typeId, key, "子设备");
    }

    private UUID createType(Login login, UUID projectId, String key, String kind, String protocol, String network)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\":\"%s\",\"name\":\"%s\",\"deviceKind\":\"%s\","
                                .concat("\"payloadProtocol\":\"%s\",\"networkType\":\"%s\"}")
                                .formatted(key, key, kind, protocol, network)))
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

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

    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        tools.jackson.databind.JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(v -> v.startsWith("tc_refresh=")).map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    private UUID createProject(Login login, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(name))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(JSON.readTree(r.getResponse().getContentAsString()).get("id").asString());
    }

    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        String refresh = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(v -> v.startsWith("tc_refresh=")).map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                .findFirst().orElse(login.refreshToken());
        return new Login(JSON.readTree(r.getResponse().getContentAsString()).get("accessToken").asString(), refresh);
    }

    private record Login(String accessToken, String refreshToken) { }
}
