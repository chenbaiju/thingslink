package com.things.link.bootstrap.device.model;

import com.things.link.device.application.DeviceService;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * ADR0059：未版本化设备换型的真实竞争与首次绑定原子性。
 * 项目、类型、发布和设备均由API建立；锁持有者与观察器使用独立APP_ROLE连接，不修改公共测试装配。
 */
@AutoConfigureMockMvc
@DisplayName("设备换型首次版本绑定：真实竞争、幂等与回滚")
class DeviceTypeChangeVersionConcurrencyTests extends AbstractIntegrationTest {

    /** 按项目实际JSON协议解析绑定与错误事实，不从响应文本猜测结果。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 本地随机账号夹具使用的固定测试口令，不是部署凭据。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** ADR0059首次绑定后禁止跨类型修改，与既有类型共享锁忙30061区分。 */
    private static final int VERSION_CONFLICT = 30063;
    /** 主动故障发生在已写INITIAL之后，不能把前置校验失败冒充事务回滚验证。 */
    private static final String ROLLBACK_MARKER = "D112_TEST_ROLLBACK_AFTER_INITIAL";
    /** HTTP场景穿过真实认证、授权、事务和异常映射。 */
    @Autowired private MockMvc mockMvc;
    /** 与生产仓储共用数据源和事务，读取实际PID及持久化事实。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 并发和主动回滚必须调用实际Spring代理，不能用测试SQL替代业务换型。 */
    @Autowired private DeviceService deviceService;
    /** 外层事务用于观测实际连接身份及测试服务事务参与，不替换服务自身事务声明。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 随机账号建立前清理测试限流计数，避免夹具因无关的注册频率失败。 */
    @Autowired private AuthRateLimiter rateLimiter;

    /** 独占随机项目保留到容器销毁；只清线程范围，不删除共享库中其他测试的数据。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 目标类型编辑锁先到时，HTTP换型须30061且无部分写入；锁释放后原请求可以原子首次绑定。 */
    @Test
    void busyPublishedTypeRejectsHttpChangeAndSameRequestSucceedsAfterRelease() throws Exception {
        Fixture fixture = createFixture();
        String before = snapshot(fixture);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (Connection editor = appConnection()) {
            editor.setAutoCommit(false);
            try {
                setProject(editor, fixture.projectId());
                lockRow(editor, "dev_type", fixture.targetA());
                MvcResult refusal = requests.submit(() -> {
                    assertThat(TenantContext.current()).isEmpty();
                    try {
                        return changeViaHttp(fixture, fixture.targetA(), "锁释放后首次绑定");
                    } finally {
                        TenantContext.clear();
                    }
                }).get(10, TimeUnit.SECONDS);
                assertThat(refusal.getResponse().getStatus()).isEqualTo(409);
                assertThat(errorCode(refusal)).isEqualTo(30061);
                assertThat(snapshot(fixture)).isEqualTo(before);
            } finally {
                // 若NOWAIT退化为阻塞，先释放持锁连接，再回收请求线程，失败路径也有界。
                editor.rollback();
            }
        } finally {
            stopRequests(requests);
        }
        assertThat(changeViaHttp(fixture, fixture.targetA(), "锁释放后首次绑定").getResponse().getStatus()).isEqualTo(200);
        assertInitialBinding(fixture, fixture.targetA(), fixture.versionA(), "锁释放后首次绑定");
    }

    /** 实际设备锁等待决定A先到；A首次绑定后，换到不同已发布类型的B必须重查并30063，不能覆盖A。 */
    @Test
    void differentPublishedTargetsSerializeAndOnlyFirstChangeCreatesInitialBinding() throws Exception {
        Fixture fixture = createFixture();
        ChangeResults results = orderedChanges(fixture, fixture.targetB());
        assertThat(results.firstFailure()).isNull();
        assertThat(results.secondFailure()).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode().code()).isEqualTo(VERSION_CONFLICT));
        assertInitialBinding(fixture, fixture.targetA(), fixture.versionA(), "先到请求A");
        JsonNode device = JSON.readTree(snapshot(fixture)).get("device");
        assertThat(device.get("description").asString()).isEqualTo("先到请求A的描述");
        assertThat(device.get("location").asString()).isEqualTo("先到请求A的位置");
    }

    /** 相同目标的后到请求应成为普通基本信息更新，不能因首次绑定已存在而拒绝或重复追加INITIAL。 */
    @Test
    void samePublishedTargetSerializesAsMetadataEditWithoutDuplicateInitial() throws Exception {
        Fixture fixture = createFixture();
        ChangeResults results = orderedChanges(fixture, fixture.targetA());
        assertThat(results.firstFailure()).isNull();
        assertThat(results.secondFailure()).isNull();
        assertInitialBinding(fixture, fixture.targetA(), fixture.versionA(), "后到请求B");
        JsonNode device = JSON.readTree(snapshot(fixture)).get("device");
        assertThat(device.get("description").asString()).isEqualTo("后到请求B的描述");
        assertThat(device.get("location").asString()).isEqualTo("后到请求B的位置");
    }

    /** 已在真实事务内写入type/pointer/INITIAL后再失败，所有基本信息与绑定事实必须一起回滚且可重试。 */
    @Test
    void outerTransactionFailureRollsBackChangedTypePointerAndInitialTogether() throws Exception {
        Fixture fixture = createFixture();
        String before = snapshot(fixture);
        assertThat(AopUtils.isAopProxy(deviceService)).isTrue();
        assertThatThrownBy(() -> inScope(fixture.scope(), () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                currentBackendPid();
                deviceService.update(fixture.projectId(), fixture.deviceId(), fixture.targetA(),
                        "尚未提交的首次绑定", "尚未提交的描述", "尚未提交的位置");
                // 同连接必须已看见完整首次绑定，随后抛错才证明写入后的事务参与与原子回滚。
                assertInitialState(currentState(fixture), fixture.targetA(), fixture.versionA(), "尚未提交的首次绑定");
                throw new IllegalStateException(ROLLBACK_MARKER);
            });
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessage(ROLLBACK_MARKER);
        assertThat(snapshot(fixture)).isEqualTo(before);
        assertThat(changeViaHttp(fixture, fixture.targetA(), "回滚后重新首次绑定").getResponse().getStatus()).isEqualTo(200);
        assertInitialBinding(fixture, fixture.targetA(), fixture.versionA(), "回滚后重新首次绑定");
    }

    /**
     * 闸门持有设备FOR UPDATE；先确认A等待闸门，再确认B等待A的锁队列，之后才释放闸门。
     * 两个实际服务事务使用CONTROL池的两条连接，观察器必须独立借原生APP连接，不能挤占第三条池连接。
     */
    private ChangeResults orderedChanges(Fixture fixture, UUID secondTarget) throws Exception {
        assertThat(AopUtils.isAopProxy(deviceService)).isTrue();
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try (Connection gate = appConnection()) {
            gate.setAutoCommit(false);
            try {
                setProject(gate, fixture.projectId());
                int gatePid = backendPid(gate);
                lockRow(gate, "dev_device", fixture.deviceId());
                CompletableFuture<Integer> firstPid = new CompletableFuture<>();
                Future<Throwable> first = submitChange(requests, fixture, fixture.targetA(), "先到请求A", firstPid);
                int actualFirstPid = firstPid.get(5, TimeUnit.SECONDS);
                assertDatabaseLockWait(actualFirstPid, gatePid, first);

                CompletableFuture<Integer> secondPid = new CompletableFuture<>();
                Future<Throwable> second = submitChange(requests, fixture, secondTarget, "后到请求B", secondPid);
                int actualSecondPid = secondPid.get(5, TimeUnit.SECONDS);
                assertThat(actualSecondPid).isNotEqualTo(gatePid).isNotEqualTo(actualFirstPid);
                assertDatabaseLockWait(actualSecondPid, actualFirstPid, second);
                gate.rollback();
                return new ChangeResults(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                gate.rollback();
            }
        } finally {
            stopRequests(requests);
        }
    }

    /** 真实事务开始后公布PID，再进入真实服务换型；异常跨事务边界后返回，不能吞错导致错误提交。 */
    private Future<Throwable> submitChange(ExecutorService requests, Fixture fixture, UUID targetType, String name,
                                            CompletableFuture<Integer> pid) {
        return requests.submit(() -> {
            Throwable failure = catchThrowable(() -> {
                assertThat(TenantContext.current()).isEmpty();
                inScope(fixture.scope(), () -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        pid.complete(currentBackendPid());
                        deviceService.update(fixture.projectId(), fixture.deviceId(), targetType,
                                name, name + "的描述", name + "的位置");
                    });
                    return null;
                });
            });
            if (!pid.isDone()) {
                pid.completeExceptionally(failure == null ? new IllegalStateException("换型事务未公布PID") : failure);
            }
            return failure;
        });
    }

    /** 事务内身份和锁超时必须在被测服务前确定；超时仅兜底失败清理，顺序以实际阻塞图为准。 */
    private int currentBackendPid() {
        jdbcTemplate.execute("SET LOCAL statement_timeout = '20s'");
        jdbcTemplate.execute("SET LOCAL lock_timeout = '15s'");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** PostgreSQL报告真实阻塞关系才通过；等待请求提前成功/失败都不能当作正确竞争。 */
    private void assertDatabaseLockWait(int waitingPid, int blockingPid, Future<?> request) throws SQLException {
        assertThat(waitingPid).isNotEqualTo(blockingPid);
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?))")) {
            query.setInt(1, blockingPid);
            query.setInt(2, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(blockingPid);
                    if (row.getBoolean(2)) return;
                }
                assertThat(request.isDone()).as("请求不能越过指定的设备锁等待而提前结束").isFalse();
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到设备换型事务 " + waitingPid + " 等待事务 " + blockingPid);
    }

    /** 关闭线程前调用方已释放外部持锁者；数据库超时为最后兜底，不留下无限等待的工作线程。 */
    private void stopRequests(ExecutorService requests) throws InterruptedException {
        requests.shutdownNow();
        assertThat(requests.awaitTermination(25, TimeUnit.SECONDS)).as("换型测试工作线程必须退出").isTrue();
    }

    /** 每个测试拥有随机账号和项目，源类型为草稿、两个目标已发布，设备因此确实尚未版本化。 */
    private Fixture createFixture() throws Exception {
        Login owner = registerAndLogin("type-version-race-" + UUID.randomUUID() + "@example.com");
        MvcResult project = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"设备换型并发项目\",\"region\":\"sh-1\"}")).andReturn();
        assertThat(project.getResponse().getStatus()).isEqualTo(200);
        UUID projectId = responseId(project);
        Login login = switchProject(owner, projectId);
        UUID source = createType(login, projectId, "draft_source", false);
        UUID targetA = createType(login, projectId, "published_a", true);
        UUID targetB = createType(login, projectId, "published_b", true);
        MvcResult device = mockMvc.perform(post("/api/v1/projects/" + projectId + "/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"unversioned\",\"name\":\"原设备\"}".formatted(source)))
                .andReturn();
        assertThat(device.getResponse().getStatus()).isEqualTo(201);
        TenantScope scope = ownerScope(projectId);
        UUID versionA = inScope(scope, () -> publishedVersion(projectId, targetA));
        UUID versionB = inScope(scope, () -> publishedVersion(projectId, targetB));
        assertThat(versionA).isNotEqualTo(versionB);
        Fixture fixture = new Fixture(projectId, responseId(device), source, targetA, targetB, versionA, login, scope);
        JsonNode state = JSON.readTree(snapshot(fixture));
        assertThat(state.get("device").get("device_type_id").asString()).isEqualTo(source.toString());
        assertThat(state.get("device").get("thing_model_version_id").isNull()).isTrue();
        assertThat(state.get("history")).isEmpty();
        return fixture;
    }

    /** 通过真实发布生成不可变版本，不能仅手写PUBLISHED状态绕过首次发布合同。 */
    private UUID createType(Login login, UUID projectId, String key, boolean publish) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"typeKey":"%s","name":"%s","deviceKind":"DIRECT",
                         "payloadProtocol":"STANDARD","networkType":"WIFI"}
                        """.formatted(key, key))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID typeId = responseId(created);
        if (publish) {
            MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/device-types/" + typeId + "/publish")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
        return typeId;
    }

    /** 初次发布实际生成的版本身份用于精确检查，不仅比较可能跨类型重名的1.0.0文本。 */
    private UUID publishedVersion(UUID projectId, UUID typeId) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM dev_thing_model_version WHERE project_id = ? AND device_type_id = ?
                 ORDER BY version_major DESC, version_minor DESC, version_patch DESC LIMIT 1
                """, UUID.class, projectId, typeId);
    }

    /** 同一请求在锁释放或整体回滚后重试，必须仍走原HTTP更新入口。 */
    private MvcResult changeViaHttp(Fixture fixture, UUID typeId, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/projects/" + fixture.projectId() + "/devices/" + fixture.deviceId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"name\":\"%s\"}".formatted(typeId, name))).andReturn();
    }

    /** 快照覆盖设备全部字段、该设备全部绑定历史及项目类型/版本，包含更新时间和名称的部分写入也会被发现。 */
    private String snapshot(Fixture fixture) {
        return inScope(fixture.scope(), () -> currentState(fixture).toString());
    }

    /** 调用者已设置项目范围；在外层事务内读取时必须使用同一连接观察尚未提交的INITIAL。 */
    private JsonNode currentState(Fixture fixture) {
        String state = jdbcTemplate.queryForObject("""
                SELECT jsonb_build_object(
                    'device', to_jsonb(d),
                    'history', COALESCE((SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id)
                        FROM dev_device_model_binding_history h WHERE h.project_id = d.project_id AND h.device_id = d.id), '[]'::jsonb),
                    'types', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM dev_type t WHERE t.project_id = d.project_id),
                    'versions', (SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id)
                        FROM dev_thing_model_version v WHERE v.project_id = d.project_id))::text
                  FROM dev_device d WHERE d.project_id = ? AND d.id = ?
                """, String.class, fixture.projectId(), fixture.deviceId());
        return JSON.readTree(state);
    }

    /** 从新的应用读取确认提交后的type/pointer/INITIAL为一个一致事实。 */
    private void assertInitialBinding(Fixture fixture, UUID typeId, UUID versionId, String name) {
        assertInitialState(JSON.readTree(snapshot(fixture)), typeId, versionId, name);
    }

    /** 首次绑定必须恰一条、from为空、to为当前版本，不能只断言pointer非空掩盖重复历史。 */
    private void assertInitialState(JsonNode state, UUID typeId, UUID versionId, String name) {
        JsonNode device = state.get("device");
        assertThat(device.get("device_type_id").asString()).isEqualTo(typeId.toString());
        assertThat(device.get("thing_model_version_id").asString()).isEqualTo(versionId.toString());
        assertThat(device.get("name").asString()).isEqualTo(name);
        JsonNode history = state.get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("transition_type").asString()).isEqualTo("INITIAL");
        assertThat(history.get(0).get("from_model_version_id").isNull()).isTrue();
        assertThat(history.get(0).get("to_model_version_id").asString()).isEqualTo(versionId.toString());
    }

    /** 竞争前预读真实项目所有者范围，避免两个业务连接已占用时额外借连接查授权身份。 */
    private TenantScope ownerScope(UUID projectId) {
        UUID tenant = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        UUID actor = jdbcTemplate.queryForObject(
                "SELECT account_id FROM sys_project_member WHERE project_id = ? AND role = 'OWNER'", UUID.class, projectId);
        return new TenantScope(tenant, projectId, actor);
    }

    /** 范围必须在生产数据源借出事务连接之前设置；无论成功或失败均清理线程上下文。 */
    private <T> T inScope(TenantScope scope, Supplier<T> work) {
        TenantContext.set(scope);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 持锁与观察器使用原生APP_ROLE连接；不能通过owner绕过RLS或占用两个CONTROL连接。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try (PreparedStatement query = connection.prepareStatement("SELECT current_user"); ResultSet row = query.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(APP_ROLE);
            return connection;
        } catch (SQLException | RuntimeException | Error exception) {
            connection.close();
            throw exception;
        }
    }

    /** 事务局部项目范围随rollback清除；原生连接不复制任何HTTP线程状态。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.project_id', ?, true)")) {
            statement.setString(1, projectId.toString());
            statement.execute();
        }
    }

    /** 闸门必须锁到实际目标身份，空结果不能被当作已建立竞争前置。 */
    private void lockRow(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            lock.setQueryTimeout(3);
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1)).isEqualTo(id);
            }
        }
    }

    /** 物理PID用于数据库阻塞证据，不能把逻辑连接或Java线程标识当作数据库身份。 */
    private int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()"); ResultSet row = query.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    /** 随机账号只补邮箱验证前置，后续操作仍经生产登录与授权。 */
    private Login registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(registration.getResponse().getStatus()).isEqualTo(204);
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';'))).findFirst().orElseThrow();
        return new Login(JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(), refresh);
    }

    /** 使用真实项目JWT进行HTTP操作，避免把手写TenantScope误当认证链验证。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                .cookie(new Cookie("tc_refresh", login.refreshToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"%s\"}".formatted(projectId))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return new Login(JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asString(), login.refreshToken());
    }

    /** 返回实际持久化身份，禁止自行猜测API生成的UUID。 */
    private UUID responseId(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 意外200且无错误字段时返回哨兵值，使断言明确失败而不先抛空指针。 */
    private int errorCode(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.has("code") ? body.get("code").asInt() : -1;
    }

    /**
     * 单用例API夹具，所有版本ID均从实际发布结果读取。
     * @param projectId 项目隔离范围
     * @param deviceId 未版本化设备
     * @param sourceType 草稿源类型
     * @param targetA 先到请求的已发布目标
     * @param targetB 不同目标竞争场景的已发布目标
     * @param versionA 目标A的实际不可变版本身份
     * @param login 项目所有者的真实API登录
     * @param scope 服务调用所需的可信所有者范围
     */
    private record Fixture(UUID projectId, UUID deviceId, UUID sourceType, UUID targetA, UUID targetB,
                           UUID versionA, Login login, TenantScope scope) { }

    /**
     * 两个真实事务完成后的结果，null表示成功提交。
     * @param firstFailure 先到请求抛出的异常
     * @param secondFailure 后到请求抛出的异常
     */
    private record ChangeResults(Throwable firstFailure, Throwable secondFailure) { }

    /** @param accessToken API访问JWT @param refreshToken 项目切换要求的刷新Cookie */
    private record Login(String accessToken, String refreshToken) { }
}
