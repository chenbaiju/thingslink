package com.things.link.bootstrap.device.topology;

import com.things.link.device.application.DeviceTopologyService;
import com.things.link.device.application.DeviceTypeService;
import com.things.link.device.domain.DeviceType;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * D-111：设备改类型与草稿类型改分类都不能破坏已有有效拓扑角色。
 * 夹具和最终变更均经过真实 API；数据库快照核对拒绝时身份、类型、关系与投影均未被部分改写。
 */
@AutoConfigureMockMvc
@DisplayName("拓扑角色保护：设备与草稿类型修改")
class DeviceTopologyRoleApiTests extends AbstractIntegrationTest {

    /** 与当前项目 JSON 协议保持一致，不把请求解释委托给手写字符串查找。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 仅本类本地账号夹具使用的非生产口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** ADR0057 角色冲突合同，全仓错误码登记为 30060，避免与遥测领域已用编号碰撞。 */
    private static final int ROLE_CONFLICT = 30060;
    /** ADR0057 类型共享锁 NOWAIT 忙时返回 409，调用者可在原事务回滚后重试。 */
    private static final int TYPE_BUSY = 30061;
    /** 穿过认证、授权、DTO 校验及事务代理的真实控制面入口。 */
    @Autowired private MockMvc mockMvc;
    /** 数据库检查使用应用数据源和项目 RLS，不能只以 HTTP 拒绝证明没有修改。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 复用现有集成夹具的限流清理，避免初始化账号速度改变业务结果。 */
    @Autowired private AuthRateLimiter rateLimiter;
    /** 并发绑定通过生产事务代理取得共享类型锁，不能以手写 SQL 替代绑定行为。 */
    @Autowired private DeviceTopologyService topologyService;
    /** 与绑定竞争的真实类型修改服务，事务提交后必须重新核对已出现的角色。 */
    @Autowired private DeviceTypeService typeService;
    /** 显式提交屏障用于观察真实 PostgreSQL 事务次序。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 每个用例独立创建的项目所有者登录凭据。 */
    private Login owner;

    /** 每例使用随机账号及其独占租户、项目，不通过清空业务表抹除其他用例夹具；容器最终回收。 */
    @BeforeEach
    void seed() throws Exception {
        owner = registerAndLogin("topology-role-" + UUID.randomUUID() + "@example.com");
    }

    /** 不把最后一次数据库检查的 RLS 范围泄漏到下个用例。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 设备 PUT 不能把已有子设备关系改为 DIRECT；拒绝必须连同名称和原投影一起保持原样。 */
    @Test
    void changingBoundSubDeviceToDirectTypeIsRejectedWithoutMutation() throws Exception {
        Fixture fixture = createBoundTopology();
        UUID directType = createType(fixture.login(), fixture.projectId(), "direct_type", "DIRECT", "STANDARD", "WIFI");
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());

        MvcResult result = mockMvc.perform(put("/api/v1/projects/" + fixture.projectId() + "/devices/" + fixture.subId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"name\":\"不应保存的新名称\"}".formatted(directType))).andReturn();
        String after = topologySnapshot(fixture.projectId(), fixture.topologyId());
        assertSoftly(softly -> {
            softly.assertThat(result.getResponse().getStatus()).as("已有绑定子设备角色不能漂移").isEqualTo(409);
            softly.assertThat(after).as("拒绝时设备、双方类型、关系和投影应全部不变").isEqualTo(before);
        });
        assertThat(errorCode(result)).isEqualTo(ROLE_CONFLICT);
    }

    /** 草稿类型 PUT 不能将仍代理子设备的网关分类改成 SUB_DEVICE，DRAFT 不代表可以破坏已有角色。 */
    @Test
    void changingUsedGatewayDraftTypeToSubDeviceIsRejectedWithoutMutation() throws Exception {
        Fixture fixture = createBoundTopology();
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());

        MvcResult result = mockMvc.perform(put("/api/v1/projects/" + fixture.projectId()
                        + "/device-types/" + fixture.gatewayTypeId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"typeKey":"gateway_type","name":"不应保存的新分类","deviceKind":"SUB_DEVICE",
                         "payloadProtocol":"STANDARD","networkType":"ZIGBEE"}
                        """)).andReturn();
        String after = topologySnapshot(fixture.projectId(), fixture.topologyId());
        assertSoftly(softly -> {
            softly.assertThat(result.getResponse().getStatus()).as("被有效网关使用的草稿类型分类不能漂移").isEqualTo(409);
            softly.assertThat(after).as("拒绝时设备、双方类型、关系和投影应全部不变").isEqualTo(before);
        });
        assertThat(errorCode(result)).isEqualTo(ROLE_CONFLICT);
    }

    /** 清空子设备类型和把网关改为子设备类型同样破坏角色，不能只保护 DIRECT 这一种目标。 */
    @Test
    void clearingBoundTypeAndChangingGatewayToSubTypeBothRollback() throws Exception {
        Fixture fixture = createBoundTopology();
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());
        assertRejectedWithoutMutation(fixture, before,
                updateDevice(fixture.login(), fixture.projectId(), fixture.subId(), null, "不应保存的空类型"),
                409, ROLE_CONFLICT);
        assertRejectedWithoutMutation(fixture, before,
                updateDevice(fixture.login(), fixture.projectId(), fixture.gatewayId(), fixture.subTypeId(), "不应保存的网关"),
                409, ROLE_CONFLICT);
    }

    /** 有效关系引用的两端类型均不能软删；设备外键仍存在不能代替角色有效性。 */
    @Test
    void deletingEitherTypeUsedByActiveTopologyIsRejected() throws Exception {
        Fixture fixture = createBoundTopology();
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());
        for (UUID typeId : new UUID[]{fixture.gatewayTypeId(), fixture.subTypeId()}) {
            MvcResult result = mockMvc.perform(delete("/api/v1/projects/" + fixture.projectId() + "/device-types/" + typeId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())).andReturn();
            assertRejectedWithoutMutation(fixture, before, result, 409, ROLE_CONFLICT);
        }
    }

    /** 仍满足角色的草稿换型及类型名称修改必须允许，不能把角色保护扩成所有已有设备都不可修改。 */
    @Test
    void compatibleDraftTypeAndNameChangesKeepActiveTopology() throws Exception {
        Fixture fixture = createBoundTopology();
        UUID replacement = createType(fixture.login(), fixture.projectId(), "replacement_sub", "SUB_DEVICE", "STANDARD", "ZIGBEE");
        JsonNode before = JSON.readTree(topologySnapshot(fixture.projectId(), fixture.topologyId()));
        MvcResult changed = updateDevice(fixture.login(), fixture.projectId(), fixture.subId(), replacement, "合法新名称");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        MvcResult renamed = updateType(fixture.login(), fixture.projectId(), fixture.gatewayTypeId(), "gateway_type",
                "合法网关类型名称", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
        assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = JSON.readTree(topologySnapshot(fixture.projectId(), fixture.topologyId()));
        assertThat(after.get("topology")).isEqualTo(before.get("topology"));
        assertThat(after.get("sub_device").get("device_type_id").asString()).isEqualTo(replacement.toString());
        assertThat(after.get("sub_device").get("gateway_id").asString()).isEqualTo(fixture.gatewayId().toString());
        assertThat(after.get("sub_device").get("name").asString()).isEqualTo("合法新名称");
        assertThat(after.get("gateway_type").get("name").asString()).isEqualTo("合法网关类型名称");
    }

    /** 先明确解绑后，原子设备可清空类型，原网关草稿可改分类；历史关系仍保留。 */
    @Test
    void explicitUnbindAllowsSubTypeRemovalAndGatewayKindChange() throws Exception {
        Fixture fixture = createBoundTopology();
        unbind(fixture);
        String historical = inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text FROM dev_topo t WHERE id = ?", String.class, fixture.topologyId()));
        assertThat(updateDevice(fixture.login(), fixture.projectId(), fixture.subId(), null, "已解绑子设备")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(updateType(fixture.login(), fixture.projectId(), fixture.gatewayTypeId(), "gateway_type",
                "已解绑普通类型", "DIRECT", "STANDARD", "WIFI").getResponse().getStatus()).isEqualTo(200);
        JsonNode after = JSON.readTree(topologySnapshot(fixture.projectId(), fixture.topologyId()));
        assertThat(after.get("sub_device").get("device_type_id").isNull()).isTrue();
        assertThat(after.get("sub_device").get("gateway_id").isNull()).isTrue();
        assertThat(after.get("gateway_type").get("device_kind").asString()).isEqualTo("DIRECT");
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text FROM dev_topo t WHERE id = ?", String.class, fixture.topologyId())))
                .isEqualTo(historical);
    }

    /** 类型被设备引用但没有有效拓扑时可以改分类，不能把存在设备误作角色冲突。 */
    @Test
    void draftTypeWithUnboundDeviceCanChangeKind() throws Exception {
        Fixture fixture = createBoundTopology();
        UUID typeId = createType(fixture.login(), fixture.projectId(), "unbound_type", "DIRECT", "STANDARD", "WIFI");
        UUID deviceId = createDevice(fixture.login(), fixture.projectId(), typeId, "unbound_device");
        String existing = topologySnapshot(fixture.projectId(), fixture.topologyId());
        MvcResult result = updateType(fixture.login(), fixture.projectId(), typeId, "unbound_type",
                "独立网关类型", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT device_type_id FROM dev_device WHERE id = ?", UUID.class, deviceId))).isEqualTo(typeId);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT device_kind FROM dev_type WHERE id = ?", String.class, typeId))).isEqualTo("GATEWAY");
        assertThat(topologySnapshot(fixture.projectId(), fixture.topologyId())).isEqualTo(existing);
    }

    /** VIEWER 与外部项目账号仍按既有授权规则拒绝，不能借角色检查泄露其他项目设备。 */
    @Test
    void viewerAndCrossProjectRequestsRetainExistingAuthorizationBoundaries() throws Exception {
        Fixture fixture = createBoundTopology();
        String viewerEmail = "topology-viewer-" + UUID.randomUUID() + "@example.com";
        Login viewer = registerAndLogin(viewerEmail);
        UUID viewerAccount = jdbcTemplate.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, viewerEmail);
        jdbcTemplate.update("INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'VIEWER')",
                Uuid7.generate(), fixture.projectId(), viewerAccount);
        Login viewerScoped = switchProject(viewer, fixture.projectId());
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());
        assertRejectedWithoutMutation(fixture, before,
                updateDevice(viewerScoped, fixture.projectId(), fixture.subId(), fixture.subTypeId(), "越权名称"), 403, 30024);
        assertRejectedWithoutMutation(fixture, before,
                updateType(viewerScoped, fixture.projectId(), fixture.gatewayTypeId(), "gateway_type", "越权名称",
                        "GATEWAY", "STANDARD_GATEWAY", "ETHERNET"), 403, 30004);

        Login outsider = registerAndLogin("topology-outsider-" + UUID.randomUUID() + "@example.com");
        UUID otherProject = createProject(outsider);
        Login outsideScoped = switchProject(outsider, otherProject);
        assertRejectedWithoutMutation(fixture, before,
                updateDevice(outsideScoped, fixture.projectId(), fixture.subId(), fixture.subTypeId(), "跨项目名称"), 404, 50001);
        // ERROR_CODES 30001 与 S2-1 既有合同：类型入口将非成员隐藏为类型不存在；设备入口沿用项目码50001。
        assertRejectedWithoutMutation(fixture, before,
                updateType(outsideScoped, fixture.projectId(), fixture.gatewayTypeId(), "gateway_type", "跨项目名称",
                        "GATEWAY", "STANDARD_GATEWAY", "ETHERNET"), 404, 30001);
    }

    /** 类型排他锁先到达时，绑定和设备换型须 NOWAIT 回滚为可重试409，释放后同一有效操作成功。 */
    @Test
    void heldTypeUpdateLockRejectsBindingAndDeviceUpdateThenAllowsRetry() throws Exception {
        Fixture fixture = createBoundTopology();
        UUID anotherSub = createDevice(fixture.login(), fixture.projectId(), fixture.subTypeId(), "another_sub");
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());
        String unboundBefore = deviceSnapshot(fixture.projectId(), anotherSub);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor(); Connection holder = appConnection()) {
            holder.setAutoCommit(false);
            try {
                setProject(holder, fixture.projectId());
                try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM dev_type WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, fixture.subTypeId());
                    lock.setQueryTimeout(3);
                    try (var row = lock.executeQuery()) {
                        assertThat(row.next()).isTrue();
                    }
                }
                MvcResult binding = requests.submit(() -> bind(fixture.login(), fixture.projectId(),
                        anotherSub, fixture.gatewayId())).get(10, TimeUnit.SECONDS);
                assertRejectedWithoutMutation(fixture, before, binding, 409, TYPE_BUSY);
                assertThat(deviceSnapshot(fixture.projectId(), anotherSub)).isEqualTo(unboundBefore);
                MvcResult changed = requests.submit(() -> updateDevice(fixture.login(), fixture.projectId(),
                        fixture.subId(), fixture.subTypeId(), "忙时不应保存")).get(10, TimeUnit.SECONDS);
                assertRejectedWithoutMutation(fixture, before, changed, 409, TYPE_BUSY);
            } finally {
                holder.rollback();
            }
        }
        assertThat(bind(fixture.login(), fixture.projectId(), anotherSub, fixture.gatewayId())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(updateDevice(fixture.login(), fixture.projectId(), fixture.subId(), fixture.subTypeId(), "重试成功")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT gateway_id FROM dev_device WHERE id = ?", UUID.class, anotherSub))).isEqualTo(fixture.gatewayId());
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT name FROM dev_device WHERE id = ?", String.class, fixture.subId()))).isEqualTo("重试成功");
    }

    /** 绑定先取得类型共享锁时，分类修改真实等待提交，之后必须看见新关系并按角色冲突回滚。 */
    @Test
    void typeMutationWaitsForBindingCommitAndThenRejectsNewlyVisibleRole() throws Exception {
        Fixture fixture = createBoundTopology();
        unbind(fixture);
        String typeBefore = inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text FROM dev_type t WHERE id = ?", String.class, fixture.gatewayTypeId()));
        TenantScope scope = ownerScope(fixture.projectId());
        CompletableFuture<Integer> bindingPid = new CompletableFuture<>();
        CompletableFuture<Integer> mutationPid = new CompletableFuture<>();
        CountDownLatch releaseBinding = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = executor.submit(() -> withScope(scope,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        topologyService.bind(fixture.projectId(), fixture.subId(), fixture.gatewayId());
                        bindingPid.complete(currentBackendPid());
                        awaitRelease(releaseBinding);
                    })));
            int blocker = bindingPid.get(10, TimeUnit.SECONDS);
            var mutation = executor.submit(() -> withScope(scope, () -> assertThatThrownBy(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        mutationPid.complete(currentBackendPid());
                        typeService.update(fixture.projectId(), fixture.gatewayTypeId(), "gateway_type", "不能提交的分类",
                                DeviceType.DeviceKind.SUB_DEVICE, DeviceType.PayloadProtocol.STANDARD,
                                DeviceType.NetworkType.ZIGBEE);
                    })).isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo(ROLE_CONFLICT))));
            try {
                assertDatabaseLockWait(mutationPid.get(10, TimeUnit.SECONDS), blocker);
            } finally {
                releaseBinding.countDown();
            }
            binding.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
        } finally {
            releaseBinding.countDown();
        }
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text FROM dev_type t WHERE id = ?", String.class, fixture.gatewayTypeId())))
                .isEqualTo(typeBefore);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                Integer.class, fixture.subId()))).isEqualTo(1);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT gateway_id FROM dev_device WHERE id = ?", UUID.class, fixture.subId())))
                .isEqualTo(fixture.gatewayId());
    }

    /** ADR0058：网关状态写锁允许入口KEY SHARE，却阻止数据库守卫SHARE；HTTP必须整体回滚为30062。 */
    @Test
    void databaseGuardLockConflictRollsBackHttpBindingAndAllowsRetry() throws Exception {
        Fixture fixture = createBoundTopology();
        UUID anotherSub = createDevice(fixture.login(), fixture.projectId(), fixture.subTypeId(), "guard_busy_sub");
        String before = topologySnapshot(fixture.projectId(), fixture.topologyId());
        String subBefore = deviceSnapshot(fixture.projectId(), anotherSub);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor(); Connection holder = appConnection()) {
            holder.setAutoCommit(false);
            try {
                setProject(holder, fixture.projectId());
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT id FROM dev_device WHERE project_id = ? AND id = ? FOR NO KEY UPDATE")) {
                    lock.setQueryTimeout(3);
                    lock.setObject(1, fixture.projectId());
                    lock.setObject(2, fixture.gatewayId());
                    try (var row = lock.executeQuery()) {
                        assertThat(row.next()).isTrue();
                    }
                }
                MvcResult rejected = requests.submit(() -> bind(fixture.login(), fixture.projectId(),
                        anotherSub, fixture.gatewayId())).get(10, TimeUnit.SECONDS);
                assertRejectedWithoutMutation(fixture, before, rejected, 409, 30062);
                assertThat(deviceSnapshot(fixture.projectId(), anotherSub)).isEqualTo(subBefore);
                assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM dev_topo WHERE sub_device_id = ?", Integer.class, anotherSub))).isZero();
            } finally {
                // 若实现退化成等待，必须先释放持锁连接再关闭请求线程，避免测试被错误实现永久挂住。
                holder.rollback();
            }
        }
        assertThat(bind(fixture.login(), fixture.projectId(), anotherSub, fixture.gatewayId())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(inProject(fixture.projectId(), () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL",
                Integer.class, anotherSub))).isEqualTo(1);
    }

    /** 通过真实登录、项目和绑定 API 生成一条角色正确的有效关系。 */
    private Fixture createBoundTopology() throws Exception {
        UUID projectId = createProject(owner);
        Login login = switchProject(owner, projectId);
        UUID gatewayTypeId = createType(login, projectId, "gateway_type", "GATEWAY", "STANDARD_GATEWAY", "ETHERNET");
        UUID subTypeId = createType(login, projectId, "sub_type", "SUB_DEVICE", "STANDARD", "ZIGBEE");
        UUID gatewayId = createDevice(login, projectId, gatewayTypeId, "gateway");
        UUID subId = createDevice(login, projectId, subTypeId, "sub_device");
        MvcResult topology = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-topologies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subDeviceId\":\"%s\",\"gatewayId\":\"%s\"}".formatted(subId, gatewayId))).andReturn();
        assertThat(topology.getResponse().getStatus()).isEqualTo(201);
        return new Fixture(projectId, gatewayTypeId, subTypeId, gatewayId, subId, responseId(topology), login);
    }

    /** 草稿类型允许调用修改入口，避免发布后冻结规则提前拒绝而掩盖本次角色漏洞。 */
    private UUID createType(Login login, UUID projectId, String key, String kind, String protocol, String network)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"typeKey":"%s","name":"%s","deviceKind":"%s","payloadProtocol":"%s","networkType":"%s"}
                        """.formatted(key, key, kind, protocol, network))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("status").asString()).isEqualTo("DRAFT");
        return responseId(result);
    }

    /** 设备类型由生产 API 验证，数据库不直接伪造网关或子设备身份。 */
    private UUID createDevice(Login login, UUID projectId, UUID typeId, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"%s\",\"name\":\"%s\"}"
                        .formatted(typeId, key, key))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return responseId(result);
    }

    /** 获取原关系、两端设备和当前类型完整快照，验证失败不能留下类型或更新时间的部分写入。 */
    private String topologySnapshot(UUID projectId, UUID topologyId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject("""
                SELECT jsonb_build_object('topology', to_jsonb(t), 'gateway', to_jsonb(g), 'sub_device', to_jsonb(s),
                                          'gateway_type', to_jsonb(gt), 'sub_type', to_jsonb(st))::text
                  FROM dev_topo t JOIN dev_device g ON g.id = t.gateway_device_id
                  JOIN dev_device s ON s.id = t.sub_device_id
                  LEFT JOIN dev_type gt ON gt.id = g.device_type_id
                  LEFT JOIN dev_type st ON st.id = s.device_type_id WHERE t.id = ?
                """, String.class, topologyId));
    }

    /** 单设备快照覆盖尚未绑定的对象，不能因原关系未变而遗漏失败绑定留下的投影。 */
    private String deviceSnapshot(UUID projectId, UUID deviceId) {
        return inProject(projectId, () -> jdbcTemplate.queryForObject(
                "SELECT to_jsonb(d)::text FROM dev_device d WHERE id = ?", String.class, deviceId));
    }

    /** 每次数据库读取都显式设置当前项目，避免 fail-closed 空结果被误当作操作成功。 */
    private <T> T inProject(UUID projectId, Supplier<T> action) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 通过项目所有者的真实账号生成服务调用范围，元数据在竞争事务开始前一次读完。 */
    private TenantScope ownerScope(UUID projectId) {
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        UUID accountId = jdbcTemplate.queryForObject(
                "SELECT account_id FROM sys_project_member WHERE project_id = ? AND role = 'OWNER'", UUID.class, projectId);
        return new TenantScope(tenantId, projectId, accountId);
    }

    /** 并发线程只使用预先读取的可信范围，不在两个业务连接已占满时再借元数据连接。 */
    private void withScope(TenantScope scope, Runnable action) {
        TenantContext.set(scope);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 控制面解绑使用已有端点，历史关闭行为不能由测试直写代替。 */
    private void unbind(Fixture fixture) throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/projects/" + fixture.projectId()
                        + "/device-topologies/" + fixture.subId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    /** 统一绑定调用同时用于正常提交与锁忙重试，保证重试没有换一种写入入口。 */
    private MvcResult bind(Login login, UUID projectId, UUID subId, UUID gatewayId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-topologies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subDeviceId\":\"%s\",\"gatewayId\":\"%s\"}".formatted(subId, gatewayId))).andReturn();
    }

    /** 包括显式 JSON null 的设备换型入口，不以省略字段模糊清空类型的合同。 */
    private MvcResult updateDevice(Login login, UUID projectId, UUID deviceId, UUID typeId, String name) throws Exception {
        String typeJson = typeId == null ? "null" : "\"" + typeId + "\"";
        return mockMvc.perform(put("/api/v1/projects/" + projectId + "/devices/" + deviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":%s,\"name\":\"%s\"}".formatted(typeJson, name))).andReturn();
    }

    /** 类型变更始终包含与目标分类兼容的协议，避免被更早的矩阵校验挡住而产生假阳性。 */
    private MvcResult updateType(Login login, UUID projectId, UUID typeId, String key, String name,
                                 String kind, String protocol, String network) throws Exception {
        return mockMvc.perform(put("/api/v1/projects/" + projectId + "/device-types/" + typeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"typeKey":"%s","name":"%s","deviceKind":"%s","payloadProtocol":"%s","networkType":"%s"}
                        """.formatted(key, name, kind, protocol, network))).andReturn();
    }

    /** 不只检查响应；授权、角色或瞬时锁忙拒绝后完整关系和双方身份必须与请求前相同。 */
    private void assertRejectedWithoutMutation(Fixture fixture, String before, MvcResult result,
                                                int status, int code) throws Exception {
        String after = topologySnapshot(fixture.projectId(), fixture.topologyId());
        int actualCode = errorCode(result);
        assertSoftly(softly -> {
            softly.assertThat(result.getResponse().getStatus()).isEqualTo(status);
            softly.assertThat(actualCode).isEqualTo(code);
            softly.assertThat(after).as("失败事务不能部分改写设备/类型/关系").isEqualTo(before);
        });
    }

    /** 200/204 误成功时返回哨兵错误码，保留 HTTP 与数据库变更的并列断言而非空指针中断。 */
    private int errorCode(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body.isBlank()) {
            return -1;
        }
        JsonNode code = JSON.readTree(body).get("code");
        return code == null ? -1 : code.asInt();
    }

    /** 项目创建实际合同为 HTTP200，与设备/类型创建201不同，前置断言显式核对。 */
    private UUID createProject(Login login) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"拓扑角色保护项目\",\"region\":\"sh-1\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return responseId(result);
    }

    /** 独立 APP_ROLE 连接用于锁持有者和只读观察器，不挤占测试 CONTROL 池的两个业务连接。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT current_user")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(APP_ROLE);
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    /** 原生锁持有者设项目范围后仅锁定本用例类型，不用迁移 owner 绕过 RLS。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT set_config('app.project_id', ?, true)")) {
            query.setString(1, projectId.toString());
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
        }
    }

    /** 获取业务事务实际 PID 并限制意外锁等待；正常推进由数据库阻塞关系决定。 */
    private int currentBackendPid() {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '15s'");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 只有观察到两个不同 PID 的真实 PostgreSQL 阻塞关系才允许第一事务提交。 */
    private void assertDatabaseLockWait(int waitingPid, int blockingPid) throws SQLException {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?))")) {
            query.setInt(1, blockingPid);
            query.setInt(2, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (row.getBoolean(2)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到类型修改事务 " + waitingPid + " 等待绑定事务 " + blockingPid);
    }

    /** 屏障有有限超时，任何断言失败仍由 finally 释放，不遗留持锁线程。 */
    private void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(15, TimeUnit.SECONDS)).as("观察实际锁等待后释放绑定").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("类型并发验证被中断", exception);
        }
    }

    /** 新建账号后仅补已验证邮箱这一登录前置，业务角色操作仍经正常认证授权链。 */
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(registration.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';'))).findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    /** 项目切换产生真实项目 JWT，不能用测试自造租户上下文替代最终 API 操作。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return new Login(JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(),
                login.refreshToken());
    }

    /** 创建响应中的真实主键用于后续 API 与数据库断言。 */
    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /**
     * 项目中一条有效绑定的真实 API 身份。
     * @param projectId 项目隔离轴
     * @param gatewayTypeId 草稿网关类型
     * @param subTypeId 草稿子设备类型
     * @param gatewayId 网关设备
     * @param subId 子设备
     * @param topologyId 有效关系
     * @param login 项目所有者凭据
     */
    private record Fixture(UUID projectId, UUID gatewayTypeId, UUID subTypeId, UUID gatewayId, UUID subId,
                           UUID topologyId, Login login) { }

    /**
     * 登录响应的本地测试身份。
     * @param accessToken 授权请求使用的 JWT
     * @param refreshToken 项目切换必须携带的刷新 Cookie
     */
    private record Login(String accessToken, String refreshToken) { }
}
