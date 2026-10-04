package com.things.link.bootstrap.device.topology;

import com.things.link.device.application.DeviceService;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.DeviceTopologyService;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 设备拓扑（网关-子设备绑定）API 端到端测试（S10-1a）。
 *
 * <p>放在 bootstrap：绑定要同时经过 iam 登录、project 项目切换与 device 接口，且要验证
 * 数据库级 DEFERRABLE 约束触发器对旁路篡改的拦截，业务模块之间不能为测试互相循环依赖。</p>
 */
@AutoConfigureMockMvc
@DisplayName("设备拓扑接口（S10-1a）")
class DeviceTopologyApiTests extends AbstractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthRateLimiter rateLimiter;
    /** 通过真实状态机制造曾在线的子设备，避免测试直接篡改 ADR 0033 的双写投影。 */
    @Autowired private DeviceTopologyIngestionService topologyIngestionService;
    /** 删除经真实 Spring 代理加入外层事务，验证 ADR 0056 要求的原子性。 */
    @Autowired private DeviceService deviceService;
    /** 并发测试走实际绑定应用服务，不以手写拓扑 SQL 替代被测业务。 */
    @Autowired private DeviceTopologyService topologyService;
    /** 显式控制提交屏障，观察两个实际数据库事务之间的锁关系。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 测试夹具扩容走与生产相同的 CAS 绑定与提交后缓存失效，不改任何生产守卫。 */
    @Autowired private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    private Login owner;
    private Login viewer;
    private UUID viewerAccountId;

    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        // 命令是审计事实不随设备级联删除，测试清场按外键依赖显式逆序；dev_topo/dev_device/dev_type
        // 都由「硬删除项目」级联清理（生产业务删除是软删除，不会触发本级联）。
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
        owner = registerAndLogin("owner-topo@example.com");
        viewer = registerAndLogin("viewer-topo@example.com");
        viewerAccountId = accountId("viewer-topo@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 绑定→列表→换绑→解绑的完整闭环，验证投影与权威事实同步维护。 */
    @Test
    void ownerBindsRebindsAndUnbindsSubDevice() throws Exception {
        UUID projectId = createProject(owner, "拓扑项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID gatewayBId = createGatewayDevice(scoped, projectId, "gw_02");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";

        // 绑定到 gw_01
        MvcResult bound = bind(scoped, topoPath, subId, gatewayId);
        assertThat(bound.getResponse().getStatus()).isEqualTo(201);
        JsonNode boundBody = JSON.readTree(bound.getResponse().getContentAsString());
        assertThat(boundBody.get("gatewayDeviceId").asString()).isEqualTo(gatewayId.toString());
        assertThat(boundBody.get("subDeviceId").asString()).isEqualTo(subId.toString());
        assertThat(boundBody.get("onlineStatus").asString()).isEqualTo("UNKNOWN");
        assertThat(gatewayIdOf(projectId, subId)).isEqualTo(gatewayId);

        // 列表只看到当前有效绑定
        JsonNode list = JSON.readTree(mockMvc.perform(get(topoPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("gatewayId", gatewayId.toString())).andReturn()
                .getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("subDeviceId").asString()).isEqualTo(subId.toString());

        // 幂等：重复绑定到同一网关返回现有绑定
        assertThat(bind(scoped, topoPath, subId, gatewayId).getResponse().getStatus()).isEqualTo(201);
        assertThat(topologyCount(projectId)).isEqualTo(1);

        // 换绑到 gw_02：旧绑定关闭，投影更新，历史轨迹保留
        MvcResult rebound = bind(scoped, topoPath, subId, gatewayBId);
        assertThat(rebound.getResponse().getStatus()).isEqualTo(201);
        assertThat(JSON.readTree(rebound.getResponse().getContentAsString())
                .get("gatewayDeviceId").asString()).isEqualTo(gatewayBId.toString());
        assertThat(gatewayIdOf(projectId, subId)).isEqualTo(gatewayBId);
        assertThat(topologyCount(projectId)).isEqualTo(2);

        // 解绑后投影清空，历史仍保留
        assertThat(mockMvc.perform(delete(topoPath + "/" + subId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(gatewayIdOf(projectId, subId)).isNull();
        assertThat(topologyCount(projectId)).isEqualTo(2);
    }

    /** D-026：软删子设备必须关闭当前绑定并保留审计历史，不能留下仍有效的网关投影。 */
    @Test
    void deletingBoundSubDeviceClosesTopologyAndPreservesHistory() throws Exception {
        UUID projectId = createProject(owner, "删除子设备项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_delete_sub");
        UUID subId = createSubDevice(scoped, projectId, "sub_delete");
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";
        MvcResult bound = bind(scoped, topoPath, subId, gatewayId);
        assertThat(bound.getResponse().getStatus()).isEqualTo(201);
        UUID bindingId = UUID.fromString(JSON.readTree(bound.getResponse().getContentAsString())
                .get("id").asString());
        Map<String, Object> originalBinding = bindingSnapshot(projectId, bindingId);
        UUID deletingAccountId = accountId("owner-topo@example.com");

        Timestamp deleteStarted = databaseTime();
        assertThat(mockMvc.perform(delete("/api/v1/projects/" + projectId + "/devices/" + subId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        Timestamp deleteFinished = databaseTime();

        // 查询不筛 deleted_at，明确区分软删除、物理删除与仍悬挂的有效关系。
        assertThat(isDeviceDeleted(projectId, subId)).isTrue();
        assertThat(isDeviceDeleted(projectId, gatewayId)).isFalse();
        assertThat(activeTopologyCount(projectId)).isZero();
        assertThat(gatewayIdOf(projectId, subId)).isNull();
        assertThat(topologyCount(projectId)).isEqualTo(1);
        assertClosedBinding(projectId, bindingId, originalBinding, deletingAccountId, deleteStarted, deleteFinished);
    }

    /** D-026 / ADR 0033：软删网关解绑全部子设备，保留其身份，并按是否曾在线恢复统一状态面。 */
    @Test
    void deletingGatewayClosesAllBindingsAndPreservesSubDeviceStates() throws Exception {
        UUID projectId = createProject(owner, "删除网关项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_delete");
        UUID onlineSubId = createSubDevice(scoped, projectId, "sub_was_online");
        UUID inactiveSubId = createSubDevice(scoped, projectId, "sub_never_online");
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";
        MvcResult onlineBound = bind(scoped, topoPath, onlineSubId, gatewayId);
        MvcResult inactiveBound = bind(scoped, topoPath, inactiveSubId, gatewayId);
        assertThat(onlineBound.getResponse().getStatus()).isEqualTo(201);
        assertThat(inactiveBound.getResponse().getStatus()).isEqualTo(201);
        UUID onlineBindingId = UUID.fromString(JSON.readTree(onlineBound.getResponse().getContentAsString())
                .get("id").asString());
        UUID inactiveBindingId = UUID.fromString(JSON.readTree(inactiveBound.getResponse().getContentAsString())
                .get("id").asString());
        Map<String, Object> originalOnlineBinding = bindingSnapshot(projectId, onlineBindingId);
        Map<String, Object> originalInactiveBinding = bindingSnapshot(projectId, inactiveBindingId);
        UUID deletingAccountId = accountId("owner-topo@example.com");
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        Instant lastOnlineAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        topologyIngestionService.ingest(new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId,
                gatewayId, DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_was_online", null, null,
                lastOnlineAt, "trace-delete-gateway"));
        assertThat(deviceStatus(projectId, onlineSubId)).isEqualTo("ONLINE");
        assertThat(deviceStatus(projectId, inactiveSubId)).isEqualTo("INACTIVE");

        Timestamp deleteStarted = databaseTime();
        assertThat(mockMvc.perform(delete("/api/v1/projects/" + projectId + "/devices/" + gatewayId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
        Timestamp deleteFinished = databaseTime();

        assertThat(isDeviceDeleted(projectId, gatewayId)).isTrue();
        assertThat(isDeviceDeleted(projectId, onlineSubId)).isFalse();
        assertThat(isDeviceDeleted(projectId, inactiveSubId)).isFalse();
        assertThat(activeTopologyCount(projectId)).isZero();
        assertThat(gatewayIdOf(projectId, onlineSubId)).isNull();
        assertThat(gatewayIdOf(projectId, inactiveSubId)).isNull();
        assertThat(deviceStatus(projectId, onlineSubId)).isEqualTo("OFFLINE");
        assertThat(deviceStatus(projectId, inactiveSubId)).isEqualTo("INACTIVE");
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT last_online_at FROM dev_device WHERE id = ?", Timestamp.class, onlineSubId)))
                .isEqualTo(Timestamp.from(lastOnlineAt));
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT last_online_at FROM dev_device WHERE id = ?", Timestamp.class, inactiveSubId))).isNull();
        assertThat(topologyCount(projectId)).isEqualTo(2);
        assertClosedBinding(projectId, onlineBindingId, originalOnlineBinding, deletingAccountId, deleteStarted, deleteFinished);
        assertClosedBinding(projectId, inactiveBindingId, originalInactiveBinding, deletingAccountId, deleteStarted, deleteFinished);
    }

    /** ADR 0033/0056：控制面解绑在线子设备应降离线，未曾在线者保持未激活且不伪造时间。 */
    @Test
    void controlPlaneUnbindPreservesLastOnlineEvidence() throws Exception {
        UUID projectId = createProject(owner, "解绑状态项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_unbind");
        UUID onlineSub = createSubDevice(scoped, projectId, "sub_online");
        UUID inactiveSub = createSubDevice(scoped, projectId, "sub_inactive");
        String path = "/api/v1/projects/" + projectId + "/device-topologies";
        assertThat(bind(scoped, path, onlineSub, gatewayId).getResponse().getStatus()).isEqualTo(201);
        assertThat(bind(scoped, path, inactiveSub, gatewayId).getResponse().getStatus()).isEqualTo(201);
        Instant lastOnline = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        topologyIngestionService.ingest(topologyMessage(projectId, gatewayId,
                DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_online", lastOnline));

        for (UUID sub : new UUID[]{onlineSub, inactiveSub}) {
            assertThat(mockMvc.perform(delete(path + "/" + sub)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                    .getResponse().getStatus()).isEqualTo(204);
            assertThat(gatewayIdOf(projectId, sub)).isNull();
        }
        assertThat(deviceStatus(projectId, onlineSub)).isEqualTo("OFFLINE");
        assertThat(lastOnlineAt(projectId, onlineSub)).isEqualTo(Timestamp.from(lastOnline));
        assertThat(deviceStatus(projectId, inactiveSub)).isEqualTo("INACTIVE");
        assertThat(lastOnlineAt(projectId, inactiveSub)).isNull();
        assertThat(activeTopologyCount(projectId)).isZero();
    }

    /** 软删闭环不能扩大原权限：VIEWER、外部项目账号及当前项目下的外部设备均不得修改关系。 */
    @Test
    void deletingDeviceRejectsViewerAndCrossProjectWithoutChangingTopology() throws Exception {
        UUID projectId = createProject(owner, "删除授权项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_authorized");
        UUID subId = createSubDevice(scoped, projectId, "sub_authorized");
        assertThat(bind(scoped, "/api/v1/projects/" + projectId + "/device-topologies", subId, gatewayId)
                .getResponse().getStatus()).isEqualTo(201);
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        MvcResult forbidden = mockMvc.perform(delete("/api/v1/projects/" + projectId + "/devices/" + gatewayId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerScoped.accessToken())).andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(forbidden)).isEqualTo(30024);

        Login outsider = registerAndLogin("outsider-delete-topo@example.com");
        UUID outsideProject = createProject(outsider, "外部删除项目");
        Login outsideScoped = switchProject(outsider, outsideProject);
        MvcResult hiddenProject = mockMvc.perform(delete("/api/v1/projects/" + projectId + "/devices/" + gatewayId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideScoped.accessToken())).andReturn();
        assertThat(hiddenProject.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(hiddenProject)).isEqualTo(50001);
        MvcResult hiddenDevice = mockMvc.perform(delete("/api/v1/projects/" + outsideProject + "/devices/" + gatewayId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsideScoped.accessToken())).andReturn();
        assertThat(hiddenDevice.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(hiddenDevice)).isEqualTo(30020);
        assertThat(isDeviceDeleted(projectId, gatewayId)).isFalse();
        assertThat(gatewayIdOf(projectId, subId)).isEqualTo(gatewayId);
        assertThat(activeTopologyCount(projectId)).isEqualTo(1);
    }

    /** 显式回滚外层事务，证明软删标记、权威解绑和在线投影不会部分提交。 */
    @Test
    void outerRollbackRestoresDeletionBindingAndOnlineProjectionTogether() throws Exception {
        UUID projectId = createProject(owner, "删除回滚项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_rollback");
        UUID subId = createSubDevice(scoped, projectId, "sub_rollback");
        assertThat(bind(scoped, "/api/v1/projects/" + projectId + "/device-topologies", subId, gatewayId)
                .getResponse().getStatus()).isEqualTo(201);
        Instant lastOnline = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        topologyIngestionService.ingest(topologyMessage(projectId, gatewayId,
                DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN, "sub_rollback", lastOnline));
        asOwner(projectId, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            deviceService.delete(projectId, gatewayId);
            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM dev_device WHERE id = ?",
                    Boolean.class, gatewayId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_topo WHERE unbound_at IS NULL",
                    Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT gateway_id FROM dev_device WHERE id = ?",
                    UUID.class, subId)).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM dev_device WHERE id = ?",
                    String.class, subId)).isEqualTo("OFFLINE");
            status.setRollbackOnly();
        }));
        assertThat(isDeviceDeleted(projectId, gatewayId)).isFalse();
        assertThat(activeTopologyCount(projectId)).isEqualTo(1);
        assertThat(gatewayIdOf(projectId, subId)).isEqualTo(gatewayId);
        assertThat(deviceStatus(projectId, subId)).isEqualTo("ONLINE");
        assertThat(lastOnlineAt(projectId, subId)).isEqualTo(Timestamp.from(lastOnline));
    }

    /** 删除持有网关排他锁时，新绑定必须真实等待；提交后重查已删网关并拒绝建立悬挂关系。 */
    @Test
    void gatewayDeletionBlocksConcurrentNewBindingUntilCommitted() throws Exception {
        UUID projectId = createProject(owner, "删除并发项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_delete_race");
        UUID subId = createSubDevice(scoped, projectId, "sub_delete_race");
        CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
        CompletableFuture<Integer> bindingPid = new CompletableFuture<>();
        CountDownLatch releaseDelete = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> asOwner(projectId,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        deviceService.delete(projectId, gatewayId);
                        deletingPid.complete(currentBackendPid());
                        awaitRelease(releaseDelete);
                    })));
            int blocker = deletingPid.get(10, TimeUnit.SECONDS);
            var binding = executor.submit(() -> asOwner(projectId, () -> {
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    bindingPid.complete(currentBackendPid());
                    topologyService.bind(projectId, subId, gatewayId);
                })).isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TOPOLOGY_GATEWAY_INVALID));
            }));
            try {
                assertDatabaseLockWait(bindingPid.get(10, TimeUnit.SECONDS), blocker);
            } finally {
                releaseDelete.countDown();
            }
            deletion.get(10, TimeUnit.SECONDS);
            binding.get(10, TimeUnit.SECONDS);
        } finally {
            releaseDelete.countDown();
        }
        assertThat(isDeviceDeleted(projectId, gatewayId)).isTrue();
        assertThat(gatewayIdOf(projectId, subId)).isNull();
        assertThat(activeTopologyCount(projectId)).isZero();
    }

    /** 先完成的绑定保持网关 KEY SHARE 到提交，删除须等待后再看到并关闭该新关系。 */
    @Test
    void gatewayDeletionWaitsForBindingAndClosesItsCommittedRelationship() throws Exception {
        UUID projectId = createProject(owner, "绑定并发项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_bind_race");
        UUID subId = createSubDevice(scoped, projectId, "sub_bind_race");
        CompletableFuture<Integer> bindingPid = new CompletableFuture<>();
        CompletableFuture<Integer> deletingPid = new CompletableFuture<>();
        CountDownLatch releaseBinding = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = executor.submit(() -> asOwner(projectId,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        topologyService.bind(projectId, subId, gatewayId);
                        bindingPid.complete(currentBackendPid());
                        awaitRelease(releaseBinding);
                    })));
            int blocker = bindingPid.get(10, TimeUnit.SECONDS);
            var deletion = executor.submit(() -> asOwner(projectId,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        deletingPid.complete(currentBackendPid());
                        deviceService.delete(projectId, gatewayId);
                    })));
            try {
                assertDatabaseLockWait(deletingPid.get(10, TimeUnit.SECONDS), blocker);
            } finally {
                releaseBinding.countDown();
            }
            binding.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        } finally {
            releaseBinding.countDown();
        }
        assertThat(isDeviceDeleted(projectId, gatewayId)).isTrue();
        assertThat(gatewayIdOf(projectId, subId)).isNull();
        assertThat(activeTopologyCount(projectId)).isZero();
        assertThat(topologyCount(projectId)).isEqualTo(1);
    }

    /** 旧网关事件等到子设备行锁后必须重新读取关系，不能越过已提交的控制面换绑。 */
    @ParameterizedTest
    @EnumSource(OldGatewayAction.class)
    void oldGatewayMessageRechecksBindingAfterConcurrentRebind(OldGatewayAction action) throws Exception {
        UUID projectId = createProject(owner, "旧网关并发项目");
        Login scoped = switchProject(owner, projectId);
        UUID oldGateway = createGatewayDevice(scoped, projectId, "gw_old");
        UUID newGateway = createGatewayDevice(scoped, projectId, "gw_new");
        UUID subId = createSubDevice(scoped, projectId, "sub_rebound");
        assertThat(bind(scoped, "/api/v1/projects/" + projectId + "/device-topologies", subId, oldGateway)
                .getResponse().getStatus()).isEqualTo(201);
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        CompletableFuture<Integer> rebindPid = new CompletableFuture<>();
        CompletableFuture<Integer> eventPid = new CompletableFuture<>();
        CountDownLatch allowRebind = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var rebind = executor.submit(() -> asOwner(projectId,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("SELECT id FROM dev_device WHERE id = ? FOR UPDATE",
                                UUID.class, subId);
                        rebindPid.complete(currentBackendPid());
                        awaitRelease(allowRebind);
                        topologyService.bind(projectId, subId, newGateway);
                    })));
            int blocker = rebindPid.get(10, TimeUnit.SECONDS);
            var oldEvent = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                eventPid.complete(currentBackendPid());
                if (action == OldGatewayAction.CASCADE) {
                    topologyIngestionService.cascadeGatewayOffline(tenantId, projectId, oldGateway, Instant.now());
                } else {
                    DeviceTopologyMessage.Type type = action == OldGatewayAction.LOGIN
                            ? DeviceTopologyMessage.Type.SUB_DEVICE_LOGIN : DeviceTopologyMessage.Type.TOPO_DELETE;
                    topologyIngestionService.ingest(new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId,
                            oldGateway, type, "sub_rebound", null, null, Instant.now(), "trace-old-gateway-lock"));
                }
            }));
            try {
                assertDatabaseLockWait(eventPid.get(10, TimeUnit.SECONDS), blocker);
            } finally {
                allowRebind.countDown();
            }
            rebind.get(10, TimeUnit.SECONDS);
            oldEvent.get(10, TimeUnit.SECONDS);
        } finally {
            allowRebind.countDown();
        }
        assertThat(gatewayIdOf(projectId, subId)).isEqualTo(newGateway);
        assertThat(activeTopologyCount(projectId)).isEqualTo(1);
        assertThat(inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT online_status FROM dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                String.class, subId))).isEqualTo("UNKNOWN");
        assertThat(deviceStatus(projectId, subId)).isEqualTo("INACTIVE");
        assertThat(lastOnlineAt(projectId, subId)).isNull();
    }

    /** 省略 gatewayId 时返回项目内全部有效绑定，指定时只返回该网关的，供控制台拓扑树一次拉取。 */
    @Test
    void listsAllBindingsWithoutGatewayId() throws Exception {
        UUID projectId = createProject(owner, "拓扑树项目");
        Login scoped = switchProject(owner, projectId);
        // 用例需要 4 台设备（2 网关 + 2 子设备）才能同时证明「不带 gatewayId 返回全部两条」
        // 与「带 gatewayId 只返回该网关的一条」，超过新租户默认的 PLAN_R1_FREE 冻结上限 3 台。
        widenFixtureDeviceQuota(tenantIdOf(projectId));
        UUID gatewayA = createGatewayDevice(scoped, projectId, "gw_a");
        UUID gatewayB = createGatewayDevice(scoped, projectId, "gw_b");
        UUID subA = createSubDevice(scoped, projectId, "sub_a");
        UUID subB = createSubDevice(scoped, projectId, "sub_b");
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";

        assertThat(bind(scoped, topoPath, subA, gatewayA).getResponse().getStatus()).isEqualTo(201);
        assertThat(bind(scoped, topoPath, subB, gatewayB).getResponse().getStatus()).isEqualTo(201);

        // 不带 gatewayId：返回全部两条有效绑定
        JsonNode all = JSON.readTree(mockMvc.perform(get(topoPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())).andReturn()
                .getResponse().getContentAsString());
        assertThat(all).hasSize(2);

        // 指定 gatewayId：只返回该网关的一条
        JsonNode byGateway = JSON.readTree(mockMvc.perform(get(topoPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .queryParam("gatewayId", gatewayA.toString())).andReturn()
                .getResponse().getContentAsString());
        assertThat(byGateway).hasSize(1);
        assertThat(byGateway.get(0).get("subDeviceId").asString()).isEqualTo(subA.toString());
    }

    /** 自绑定与类型不匹配由服务端拒绝，且不泄露跨项目设备存在性。 */
    @Test
    void bindRejectsSelfLoopAndWrongKinds() throws Exception {
        UUID projectId = createProject(owner, "校验项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        UUID directId = createDirectDevice(scoped, projectId, "direct_01");
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";

        // 网关绑定自己
        assertThat(errorCode(bind(scoped, topoPath, gatewayId, gatewayId))).isEqualTo(30038);

        // 网关位置放非网关设备
        assertThat(errorCode(bind(scoped, topoPath, subId, directId))).isEqualTo(30036);

        // 子设备位置放非子设备
        assertThat(errorCode(bind(scoped, topoPath, directId, gatewayId))).isEqualTo(30037);
    }

    /** VIEWER 可读但不可绑定，服务端不能依赖前端隐藏按钮。 */
    @Test
    void viewerCannotBind() throws Exception {
        UUID projectId = createProject(owner, "只读项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        addMember(projectId, viewerAccountId, "VIEWER");
        Login viewerScoped = switchProject(viewer, projectId);
        String topoPath = "/api/v1/projects/" + projectId + "/device-topologies";

        MvcResult bound = bind(viewerScoped, topoPath, subId, gatewayId);
        assertThat(bound.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(bound)).isEqualTo(30024);
    }

    /** 项目 A 的拓扑对项目 B 完全不可见，无论走 API 还是绕过应用层直查。 */
    @Test
    void topologyIsProjectIsolated() throws Exception {
        UUID projectA = createProject(owner, "项目 A");
        Login ownerA = switchProject(owner, projectA);
        UUID gatewayA = createGatewayDevice(ownerA, projectA, "gw_a");
        UUID subA = createSubDevice(ownerA, projectA, "sub_a");
        String topoA = "/api/v1/projects/" + projectA + "/device-topologies";
        assertThat(bind(ownerA, topoA, subA, gatewayA).getResponse().getStatus()).isEqualTo(201);
        assertThat(topologyCount(projectA)).isEqualTo(1);

        Login other = registerAndLogin("other-topo@example.com");
        UUID projectB = createProject(other, "项目 B");
        Login ownerB = switchProject(other, projectB);

        // API 层：非成员以项目 A 的 projectId 访问，统一按项目不存在返回，不泄露存在性。
        MvcResult hidden = mockMvc.perform(get(topoA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.accessToken())
                        .queryParam("gatewayId", gatewayA.toString()))
                .andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(hidden)).isEqualTo(50001);

        // RLS 层：项目 B 的上下文直查 dev_topo 一行也看不到（fail-closed）。
        UUID tenantB = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectB);
        TenantContext.set(new TenantScope(tenantB, projectB, Uuid7.generate()));
        try {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_topo", Integer.class)).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    /** 旁路直写 gateway_id 与权威事实不一致时，DEFERRABLE 约束触发器在提交时拦下。 */
    @Test
    void deferredTriggerRejectsBypassProjectionWrite() throws Exception {
        UUID projectId = createProject(owner, "旁路项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        assertThat(bind(scoped, "/api/v1/projects/" + projectId + "/device-topologies", subId, gatewayId)
                .getResponse().getStatus()).isEqualTo(201);

        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            // 带着正确的项目上下文直写，仍必须被触发器拦下：投影不能脱离权威事实被单独改写。
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE dev_device SET gateway_id = NULL WHERE id = ?", subId))
                    .rootCause().hasMessageContaining("投影");
        } finally {
            TenantContext.clear();
        }
    }

    /** 即使以超级用户（绕过 RLS、无项目上下文）直写，校验角色仍能定位受影响行并拦下。 */
    @Test
    void deferredTriggerRejectsSuperuserBypassWithoutContext() throws Exception {
        UUID projectId = createProject(owner, "超级用户旁路项目");
        Login scoped = switchProject(owner, projectId);
        UUID gatewayId = createGatewayDevice(scoped, projectId, "gw_01");
        UUID subId = createSubDevice(scoped, projectId, "sub_01");
        assertThat(bind(scoped, "/api/v1/projects/" + projectId + "/device-topologies", subId, gatewayId)
                .getResponse().getStatus()).isEqualTo(201);

        // 用迁移表 owner（superuser）连接：绕过 RLS，也不设 app.project_id。
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThatThrownBy(() -> {
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE dev_device SET gateway_id = NULL WHERE id = ?")) {
                    st.setObject(1, subId);
                    st.executeUpdate();
                }
            }).hasMessageContaining("投影");
        }
    }

    /** 子设备经网关间接接入：设备独立凭据与产品一型一密都要拒绝。 */
    @Test
    void subDeviceCannotObtainIndependentCredentials() throws Exception {
        UUID projectId = createProject(owner, "凭据项目");
        Login scoped = switchProject(owner, projectId);
        UUID subTypeId = createSubDeviceType(scoped, projectId, "sub_type");
        UUID subId = createTypedDevice(scoped, projectId, subTypeId, "sub_01", "子设备");

        // 设备级凭据
        MvcResult deviceCred = mockMvc.perform(post("/api/v1/projects/" + projectId
                        + "/devices/" + subId + "/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(deviceCred.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(deviceCred)).isEqualTo(30040);

        // 产品级一型一密
        MvcResult productCred = mockMvc.perform(post("/api/v1/projects/" + projectId
                        + "/device-types/" + subTypeId + "/product-credential")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()))
                .andReturn();
        assertThat(productCred.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(productCred)).isEqualTo(30040);
    }

    /** 三条旧网关写路径具有不同结果，分别覆盖重查而非用一种事件推断全部安全。 */
    private enum OldGatewayAction {
        /** 上线不能污染新网关的在线证明。 */ LOGIN,
        /** 解绑不能关闭新网关的关系。 */ DELETE,
        /** 离线级联不能改写已经换绑的统一状态。 */ CASCADE
    }

    /** 在 OWNER 身份下进入生产服务，元数据须在业务事务开始前读完，避免并发时额外占用 CONTROL 连接。 */
    private void asOwner(UUID projectId, Runnable action) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        UUID actorId = accountId("owner-topo@example.com");
        TenantContext.set(new TenantScope(tenantId, projectId, actorId));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 读取实际后端 PID 并设置事务内锁等待上限，失败用例也不得无限占住测试 JVM。 */
    private int currentBackendPid() {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '15s'");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /**
     * 以独立 APP_ROLE 观察连接核对 PostgreSQL 实际阻塞关系，不以睡眠长短推断并发已发生。
     * 两条业务事务已占满测试 CONTROL 池；观察器不能再向同池借第三条连接，也不能扩大池掩盖故障。
     */
    private void assertDatabaseLockWait(int waitingPid, int blockingPid) throws SQLException {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
             PreparedStatement probe = observer.prepareStatement(
                     "SELECT current_user, pg_backend_pid(), ? = ANY(pg_blocking_pids(?))")) {
            probe.setInt(1, blockingPid);
            probe.setInt(2, waitingPid);
            probe.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (var result = probe.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(APP_ROLE);
                    assertThat(result.getInt(2)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (result.getBoolean(3)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("没有观察到后端 " + waitingPid + " 被后端 " + blockingPid + " 的数据库锁阻塞");
    }

    /** 控制第一事务提交；即使用例中途断言失败也有超时兜底而不会遗留后台任务。 */
    private void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(15, TimeUnit.SECONDS)).as("数据库阻塞已验证后释放第一事务").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发验证屏障被中断", exception);
        }
    }

    /** 平台接收时刻保留微秒精度，与 PostgreSQL timestamptz 比较不引入截断噪声。 */
    private DeviceTopologyMessage topologyMessage(UUID projectId, UUID gatewayId, DeviceTopologyMessage.Type type,
                                                   String subDeviceKey, Instant receivedAt) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        return new DeviceTopologyMessage(Uuid7.generate(), tenantId, projectId, gatewayId, type,
                subDeviceKey, null, null, receivedAt, "trace-control-topology");
    }

    /** 解绑只影响可达性；曾在线时间作为历史事实不能被清空或刷新。 */
    private Timestamp lastOnlineAt(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT last_online_at FROM dev_device WHERE id = ?", Timestamp.class, deviceId));
    }

    // --- helpers ---

    private MvcResult bind(Login login, String topoPath, UUID subDeviceId, UUID gatewayId) throws Exception {
        return mockMvc.perform(post(topoPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subDeviceId\":\"%s\",\"gatewayId\":\"%s\"}"
                                .formatted(subDeviceId, gatewayId)))
                .andReturn();
    }

    /** 在项目 RLS 上下文中执行直读，避免 fail-closed 把「有数据」读成空。 */
    private <T> T inProject(UUID projectId, Supplier<T> action) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?",
                UUID.class, projectId);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 直接读投影列，核验权威事实与物化投影同步。 */
    private UUID gatewayIdOf(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT gateway_id FROM dev_device WHERE id = ?", UUID.class, deviceId));
    }

    /** 读取软删除标记而不隐藏被删行，证明设备历史仍实际存在。 */
    private boolean isDeviceDeleted(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM dev_device WHERE id = ?", Boolean.class, deviceId));
    }

    /** 统一状态面是设备列表和统计的直接来源，删除后的解绑必须遵循 ADR 0033。 */
    private String deviceStatus(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT status FROM dev_device WHERE id = ?", String.class, deviceId));
    }

    /** 有效关系按权威表判断，不能以 API 隐藏软删设备替代 D-026 的真正解绑。 */
    private int activeTopologyCount(UUID projectId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo WHERE unbound_at IS NULL", Integer.class));
    }

    /** 保存绑定审计字段，验证删除只关闭当前关系而不重写创建时的身份与时间。 */
    private Map<String, Object> bindingSnapshot(UUID projectId, UUID bindingId) {
        return inProject(projectId, () -> jdbcTemplate.queryForMap("""
                SELECT gateway_device_id, sub_device_id, bound_by, bound_at, unbound_by, unbound_at
                  FROM dev_topo WHERE id = ?
                """, bindingId));
    }

    /**
     * ADR0056要求实际解绑时间；HTTP前后的数据库读取均已返回，形成与SQL now()同源的请求界限。
     * 绑定来自JVM Instant.now()，不能假设宿主与数据库容器时钟零偏差而直接排序。
     */
    private Timestamp databaseTime() {
        Timestamp timestamp = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        assertThat(timestamp).isNotNull();
        return timestamp;
    }

    /** 新发生的关闭保留绑定事实并记录实际操作者，解绑时刻须处于同一数据库读取的删除请求窗口内。 */
    private void assertClosedBinding(UUID projectId, UUID bindingId, Map<String, Object> original,
                                     UUID deletingAccountId, Timestamp deleteStarted, Timestamp deleteFinished) {
        assertThat(original).containsEntry("unbound_at", null).containsEntry("unbound_by", null);
        Map<String, Object> closed = bindingSnapshot(projectId, bindingId);
        assertThat(closed).containsEntry("gateway_device_id", original.get("gateway_device_id"))
                .containsEntry("sub_device_id", original.get("sub_device_id"))
                .containsEntry("bound_by", original.get("bound_by"))
                .containsEntry("bound_at", original.get("bound_at"))
                .containsEntry("unbound_by", deletingAccountId);
        assertThat((Timestamp) closed.get("unbound_at")).isNotNull()
                .isAfterOrEqualTo(deleteStarted).isBeforeOrEqualTo(deleteFinished);
    }

    /** 项目当前 dev_topo 总行数（含历史绑定轨迹）。 */
    private int topologyCount(UUID projectId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo", Integer.class));
    }

    private UUID createGatewayDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createGatewayType(login, projectId, key + "_type");
        return createTypedDevice(login, projectId, typeId, key, "网关");
    }

    private UUID createSubDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createSubDeviceType(login, projectId, key + "_type");
        return createTypedDevice(login, projectId, typeId, key, "子设备");
    }

    private UUID createDirectDevice(Login login, UUID projectId, String key) throws Exception {
        UUID typeId = createType(login, projectId, key + "_type", "DIRECT", "STANDARD", "WIFI");
        return createTypedDevice(login, projectId, typeId, key, "直连设备");
    }

    private UUID createGatewayType(Login login, UUID projectId, String key) throws Exception {
        return createType(login, projectId, key, "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
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
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
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

    private void addMember(UUID projectId, UUID accountId, String role) {
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?,?,?,?)",
                Uuid7.generate(), projectId, accountId, role);
    }

    private UUID accountId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
    }

    private int errorCode(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).get("code").asInt();
    }

    /** @param projectId 项目 ID @return 项目所属租户 ID */
    private UUID tenantIdOf(UUID projectId) {
        return jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
    }

    /**
     * 测试夹具专用扩容：把该夹具租户的运行时指针切到仓库既有的运行时 FREE 模板
     * （{@code code = 'FREE'}、{@code plan_template = false}，设备上限 100）。
     *
     * <p>S14-2a 起新注册租户默认绑定 {@code PLAN_R1_FREE} 冻结模板（3 设备 / 每日 1000 条），
     * 拓扑树用例需要 4 台设备才能保留「全部 vs 按网关过滤」的区分度。这里只扩大该夹具租户的额度：
     * 复用生产相同的 CAS 绑定与提交后缓存失效，**不改生产守卫，也不放宽任何
     * {@code PLAN_R1_*} 冻结模板行**。
     *
     * @param tenantId 夹具租户 ID
     */
    private void widenFixtureDeviceQuota(UUID tenantId) {
        UUID runtimeFreePolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'FREE'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        quotaPolicyAssignmentService.assign(tenantId, runtimeFreePolicyId, assignmentVersion);
    }

    private record Login(String accessToken, String refreshToken) { }
}
