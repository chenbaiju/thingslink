package com.things.link.bootstrap.device.topology;

import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 拓扑消息在线状态机端到端测试（S10-2a）。
 *
 * <p>直接调用 {@link DeviceTopologyIngestionService} 驱动状态机（Kafka 消费者只做信封校验与转发），
 * 验证绑定/解绑/上下线/注册/级联的权威事实与统一可达性投影。</p>
 */
@AutoConfigureMockMvc
@DisplayName("拓扑消息在线状态机（S10-2a）")
class DeviceTopologyIngestionTests extends AbstractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthRateLimiter rateLimiter;
    @Autowired private DeviceTopologyIngestionService ingestionService;
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
        owner = registerAndLogin("owner-topo2@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** topo/add 绑定预先存在的子设备；同 messageId 重放幂等。 */
    @Test
    void topoAddBindsPreExistingSubDeviceAndIsIdempotent() throws Exception {
        UUID projectId = createProject(owner, "拓扑项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        DeviceTopologyMessage add = message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01");

        ingestionService.ingest(add);
        assertThat(bindingGateway(projectId, subId)).isEqualTo(gatewayId);
        assertThat(topologyCount(projectId)).isEqualTo(1);

        // 同 messageId 重放：inbox 去重，不产生第二条绑定
        ingestionService.ingest(add);
        assertThat(topologyCount(projectId)).isEqualTo(1);
    }

    /** 网关不得抢占其他网关已绑定的子设备；解绑只能由绑定网关执行。 */
    @Test
    void gatewayCannotStealSubDevice() throws Exception {
        UUID projectId = createProject(owner, "抢占项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayA = createGatewayDevice(scoped, projectId, "gw_a");
        UUID gatewayB = createGatewayDevice(scoped, projectId, "gw_b");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");

        ingestionService.ingest(message(projectId, gatewayA, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));
        ingestionService.ingest(message(projectId, gatewayB, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));
        assertThat(bindingGateway(projectId, subId)).isEqualTo(gatewayA);

        // 非绑定网关解绑无效
        ingestionService.ingest(message(projectId, gatewayB, DeviceTopologyMessage.Type.TOPO_DELETE, "sub_01"));
        assertThat(bindingGateway(projectId, subId)).isEqualTo(gatewayA);

        // 绑定网关解绑生效
        ingestionService.ingest(message(projectId, gatewayA, DeviceTopologyMessage.Type.TOPO_DELETE, "sub_01"));
        assertThat(bindingGateway(projectId, subId)).isNull();
    }

    /** ADR 0033/0056：网关 topo/delete 与控制面解绑共享离线语义，不清空或捏造最后在线时间。 */
    @Test
    void topoDeleteDropsReachabilityWithoutChangingOnlineEvidence() throws Exception {
        UUID projectId = createProject(owner, "网关解绑状态项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_delete_state");
        UUID onlineSub = createSubDevice(scoped, projectId, "sub_online");
        UUID inactiveSub = createSubDevice(scoped, projectId, "sub_inactive");
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_online"));
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_inactive"));
        Instant lastOnline = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN,
                "sub_online", lastOnline));
        assertThat(deviceStatus(projectId, onlineSub)).isEqualTo("ONLINE");

        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_DELETE, "sub_online"));
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_DELETE, "sub_inactive"));

        assertThat(bindingGateway(projectId, onlineSub)).isNull();
        assertThat(bindingGateway(projectId, inactiveSub)).isNull();
        assertThat(deviceStatus(projectId, onlineSub)).isEqualTo("OFFLINE");
        assertThat(deviceStatus(projectId, inactiveSub)).isEqualTo("INACTIVE");
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT last_online_at FROM dev_device WHERE id = ?", Timestamp.class, onlineSub)))
                .isEqualTo(Timestamp.from(lastOnline));
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT last_online_at FROM dev_device WHERE id = ?", Timestamp.class, inactiveSub))).isNull();
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo WHERE unbound_at IS NOT NULL AND unbound_by IS NULL",
                Integer.class))).isEqualTo(2);
        assertThat(topologyCount(projectId)).isEqualTo(2);
    }

    /** sub/login 与 sub/logout 经 CAS 更新权威在线态并投影到统一可达性状态面。 */
    @Test
    void subDeviceLoginLogoutProjectsUnifiedStatus() throws Exception {
        UUID projectId = createProject(owner, "在线态项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));

        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_01"));
        assertThat(onlineStatus(projectId, subId)).isEqualTo("ONLINE");
        assertThat(deviceStatus(projectId, subId)).isEqualTo("ONLINE");

        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT, "sub_01"));
        assertThat(onlineStatus(projectId, subId)).isEqualTo("OFFLINE");
        assertThat(deviceStatus(projectId, subId)).isEqualTo("OFFLINE");
    }

    /** 陈旧上下线事件（早于现有状态变更时间）不得覆盖新状态。 */
    @Test
    void staleOnlineEventDoesNotOverrideNewerStatus() throws Exception {
        UUID projectId = createProject(owner, "乱序项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));

        Instant later = Instant.now();
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_01", later));
        assertThat(onlineStatus(projectId, subId)).isEqualTo("ONLINE");

        // 早于 login 的下线事件：CAS 拒绝覆盖
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGOUT, "sub_01",
                later.minusSeconds(60)));
        assertThat(onlineStatus(projectId, subId)).isEqualTo("ONLINE");
    }

    /** sub/register 创建子设备身份并绑定；同 deviceKey 重放幂等。 */
    @Test
    void registerCreatesAndBindsSubDevice() throws Exception {
        UUID projectId = createProject(owner, "注册项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        String subTypeKey = "sub_type";
        createSubDeviceType(scoped, projectId, subTypeKey);

        DeviceTopologyMessage register = message(projectId, gatewayId,
                DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER, "sub_reg", "新子设备", subTypeKey);
        ingestionService.ingest(register);

        UUID subId = deviceIdByKey(projectId, "sub_reg");
        assertThat(subId).isNotNull();
        assertThat(bindingGateway(projectId, subId)).isEqualTo(gatewayId);

        // 同 deviceKey 重放：幂等，不新建设备
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_REGISTER,
                "sub_reg", "新子设备", subTypeKey));
        assertThat(topologyCount(projectId)).isEqualTo(1);
    }

    /** 网关掉线级联其全部有效子设备投影为 OFFLINE。 */
    @Test
    void cascadeGatewayOfflineMarksSubDevicesOffline() throws Exception {
        UUID projectId = createProject(owner, "级联项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subA = createSubDevice(scoped, projectId, "sub_a");
        UUID subB = createSubDevice(scoped, projectId, "sub_b");
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_a"));
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_b"));
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_a"));
        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_b"));

        UUID tenantId = tenantOf(projectId);
        ingestionService.cascadeGatewayOffline(tenantId, projectId, gatewayId, Instant.now());

        assertThat(onlineStatus(projectId, subA)).isEqualTo("OFFLINE");
        assertThat(onlineStatus(projectId, subB)).isEqualTo("OFFLINE");
        assertThat(deviceStatus(projectId, subA)).isEqualTo("OFFLINE");
    }

    /** topo/add 与 topo/delete 处理后应经 Outbox 向网关回执 down/topo/reply。 */
    @Test
    void topoMutationEmitsReplyToOutbox() throws Exception {
        UUID projectId = createProject(owner, "回执项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        createSubDevice(scoped, projectId, "sub_01");

        ingestionService.ingest(message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD, "sub_01"));

        List<String> payloads = inProject(projectId, () -> jdbcTemplate.queryForList(
                "SELECT payload FROM sys_outbox_event WHERE event_type = ? ORDER BY available_at",
                String.class, TopologyReplyMessage.EVENT_TYPE));
        assertThat(payloads).hasSize(1);
        assertThat(JSON.readTree(payloads.get(0)).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(JSON.readTree(payloads.get(0)).get("subDeviceKey").asText()).isEqualTo("sub_01");
    }

    /**
     * P0-2c1/2c2：类型编辑或数据库角色守卫占锁都不能提交inbox或伪装成业务拒绝回执。
     * 网关行NO KEY UPDATE允许入口KEY SHARE通过，只在真实INSERT的守卫升级处失败，区别于前两个类型读锁案例。
     */
    @ParameterizedTest
    @EnumSource(LockedTopologyResource.class)
    void roleLockFailureRollsBackIngestionAndAllowsSameMessageRetry(LockedTopologyResource lockedType) throws Exception {
        UUID projectId = createProject(owner, "类型锁回滚项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_type_busy");
        UUID subId = createSubDevice(scoped, projectId, "sub_type_busy");
        UUID lockedDeviceId = lockedType == LockedTopologyResource.SUB_DEVICE_TYPE ? subId : gatewayId;
        UUID typeId = inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT device_type_id FROM dev_device WHERE id = ?", UUID.class, lockedDeviceId));
        DeviceTopologyMessage add = message(projectId, gatewayId, DeviceTopologyMessage.Type.TOPO_ADD,
                "sub_type_busy");
        boolean databaseGuard = lockedType == LockedTopologyResource.GATEWAY_DEVICE;
        UUID lockedId = databaseGuard ? gatewayId : typeId;

        // 编辑者必须是真实应用角色、独立物理连接；owner 绕过 RLS 或 Mockito 异常都不能证明实际锁合同。
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (Connection typeEditor = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD)) {
            typeEditor.setAutoCommit(false);
            try {
                try (PreparedStatement scope = typeEditor.prepareStatement(
                        "SELECT current_user, set_config('app.project_id', ?, true)")) {
                    scope.setString(1, projectId.toString());
                    try (ResultSet row = scope.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo(APP_ROLE);
                    }
                }
                try (PreparedStatement lock = typeEditor.prepareStatement(
                        databaseGuard
                                ? "SELECT id FROM dev_device WHERE project_id = ? AND id = ? FOR NO KEY UPDATE"
                                : "SELECT id FROM dev_type WHERE project_id = ? AND id = ? FOR UPDATE")) {
                    lock.setQueryTimeout(3);
                    lock.setObject(1, projectId);
                    lock.setObject(2, lockedId);
                    try (ResultSet row = lock.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getObject(1, UUID.class)).isEqualTo(lockedId);
                    }
                }

                var attempt = requests.submit(() -> {
                    assertThat(TenantContext.current()).as("真实数据面请求线程没有 HTTP 租户上下文").isEmpty();
                    try {
                        ingestionService.ingest(add);
                    } finally {
                        TenantContext.clear();
                    }
                });
                // 如果 NOWAIT 回归成阻塞读取，Future 超时必须失败，并先释放编辑锁再等待请求线程退出。
                assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class, execution ->
                                assertThat(execution.getCause())
                                        .isInstanceOfSatisfying(CannotAcquireLockException.class, error ->
                                                assertThat(error.getMostSpecificCause())
                                                        .isInstanceOfSatisfying(SQLException.class, sql ->
                                                                assertThat(sql.getSQLState()).isEqualTo("55P03"))));
                assertThat(inboxCount(projectId, add.messageId())).isZero();
                assertThat(topologyCount(projectId)).isZero();
                assertThat(bindingGateway(projectId, subId)).isNull();
                assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                        "SELECT gateway_id FROM dev_device WHERE id = ?", UUID.class, subId))).isNull();
                assertThat(topologyReplyCount(projectId)).isZero();
            } finally {
                typeEditor.rollback();
            }
        } finally {
            // 此处连接的回滚/关闭已经完成；逆序等待可能在阻塞读取回归时把测试永久挂住。
            requests.shutdownNow();
            assertThat(requests.awaitTermination(10, TimeUnit.SECONDS))
                    .as("类型编辑锁释放后，摄入请求线程必须在期限内退出").isTrue();
        }

        ingestionService.ingest(add);
        assertThat(inboxCount(projectId, add.messageId())).isEqualTo(1);
        assertThat(topologyCount(projectId)).isEqualTo(1);
        assertThat(bindingGateway(projectId, subId)).isEqualTo(gatewayId);
        assertThat(topologyReplyCount(projectId)).isEqualTo(1);
        List<String> replies = inProject(projectId, () -> jdbcTemplate.queryForList(
                "SELECT payload FROM sys_outbox_event WHERE event_type = ?",
                String.class, TopologyReplyMessage.EVENT_TYPE));
        assertThat(JSON.readTree(replies.getFirst()).get("status").asText()).isEqualTo("SUCCESS");

        // 成功后同 ID 才会被 inbox 吸收；如果失败时误提交 inbox，上面的第一次重试就会缺关系和回执。
        ingestionService.ingest(add);
        assertThat(inboxCount(projectId, add.messageId())).isEqualTo(1);
        assertThat(topologyCount(projectId)).isEqualTo(1);
        assertThat(topologyReplyCount(projectId)).isEqualTo(1);
    }

    /** 分别验证类型读锁与真实数据库守卫锁，不能只用入口失败推断写入阶段也会回滚。 */
    private enum LockedTopologyResource {
        /** 网关身份校验的类型读取。 */ GATEWAY_TYPE,
        /** 目标子设备身份校验的类型读取。 */ SUB_DEVICE_TYPE,
        /** ADR0058：拓扑INSERT中对网关设备的共享锁升级。 */ GATEWAY_DEVICE
    }

    /** 在独立借出的应用连接读取已提交 inbox，避免用被回滚事务内视图判断重放资格。 */
    private int inboxCount(UUID projectId, UUID messageId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                Integer.class, projectId, messageId));
    }

    /** 只统计当前项目的拓扑回执，其他类型的 Outbox 事实不属于本次摄入合同。 */
    private int topologyReplyCount(UUID projectId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_outbox_event WHERE event_type = ?",
                Integer.class, TopologyReplyMessage.EVENT_TYPE));
    }

    // --- helpers ---

    private DeviceTopologyMessage message(UUID projectId, UUID gatewayId, DeviceTopologyMessage.Type type,
                                          String subDeviceKey) {
        return message(projectId, gatewayId, type, subDeviceKey, Instant.now());
    }

    private DeviceTopologyMessage message(UUID projectId, UUID gatewayId, DeviceTopologyMessage.Type type,
                                          String subDeviceKey, Instant receivedAt) {
        return new DeviceTopologyMessage(Uuid7.generate(), tenantOf(projectId), projectId, gatewayId, type,
                subDeviceKey, null, null, receivedAt, "trace-test");
    }

    private DeviceTopologyMessage message(UUID projectId, UUID gatewayId, DeviceTopologyMessage.Type type,
                                          String subDeviceKey, String name, String deviceTypeKey) {
        return new DeviceTopologyMessage(Uuid7.generate(), tenantOf(projectId), projectId, gatewayId, type,
                subDeviceKey, name, deviceTypeKey, Instant.now(), "trace-test");
    }

    private UUID tenantOf(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
    }

    private <T> T inProject(UUID projectId, Supplier<T> action) {
        UUID tenantId = tenantOf(projectId);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID bindingGateway(UUID projectId, UUID subDeviceId) {
        return inProject(projectId, () -> jdbcTemplate.query(
                        "SELECT gateway_device_id FROM dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                        (rs, rowNum) -> rs.getObject("gateway_device_id", UUID.class), subDeviceId)
                .stream().findFirst().orElse(null));
    }

    private String onlineStatus(UUID projectId, UUID subDeviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT online_status FROM dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                String.class, subDeviceId));
    }

    private String deviceStatus(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT status FROM dev_device WHERE id = ?", String.class, deviceId));
    }

    private UUID deviceIdByKey(UUID projectId, String deviceKey) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT id FROM dev_device WHERE device_key = ?", UUID.class, deviceKey));
    }

    private int topologyCount(UUID projectId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo", Integer.class));
    }

    private UUID createGatewayDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createType(login, projectId, key + "_type", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
        return createTypedDevice(login, projectId, typeId, key, "网关");
    }

    private UUID createSubDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createSubDeviceType(login, projectId, key + "_type");
        return createTypedDevice(login, projectId, typeId, key, "子设备");
    }

    private UUID createSubDeviceType(Login login, UUID projectId, String key) throws Exception {
        return createType(login, projectId, key, "SUB_DEVICE", "STANDARD", "ZIGBEE");
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
        return UUID.fromString(JSON.readTree(r.getResponse().getContentAsString()).get("id").asString());
    }

    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        return new Login(JSON.readTree(r.getResponse().getContentAsString()).get("accessToken").asString(),
                login.refreshToken());
    }

    private record Login(String accessToken, String refreshToken) { }
}
