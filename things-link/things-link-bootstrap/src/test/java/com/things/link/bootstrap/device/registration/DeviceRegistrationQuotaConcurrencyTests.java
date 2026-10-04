package com.things.link.bootstrap.device.registration;

import com.things.link.device.application.DeviceRegistrationService;
import com.things.link.device.application.DeviceService;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12-P0-3a：公开注册与控制面共享架构§8.1租户设备硬限，锁后同key必须冲突而非重签凭据。
 * 独立APP事务和PostgreSQL阻塞图证明真实竞争；两种入队顺序排除只有某一入口被保护的假阳性。
 */
@DisplayName("S12-P0-3a 公开动态注册真实配额竞争")
class DeviceRegistrationQuotaConcurrencyTests extends AbstractIntegrationTest {

    /** 仅用于随机隔离产品的测试明文，数据库只保存摘要。 */
    private static final String PRODUCT_SECRET = "concurrency-fixture-product-secret-only";
    /** 快照保留全部凭据字段，重放时可发现更新或重签而非只比较数量。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 公开入口自己定位项目并注入事务RLS，工作线程不得设置HTTP身份。 */
    @Autowired private DeviceRegistrationService registrationService;
    /** 控制面使用真实成员授权和同一生产配额锁，不替换配额端口。 */
    @Autowired private DeviceService deviceService;
    /** 事务内公布实际连接PID，用于关联PostgreSQL锁队列。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 外层真实事务保持工作线程连接和服务提交边界一致。 */
    @Autowired private PlatformTransactionManager transactionManager;

    /** 同owner两项目共享最后一格；任一入口先入队都必须仅有一个成功和一个明确配额拒绝。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void controlPlaneAndPublicRegistrationCompeteForLastOwnerSlot(boolean controlFirst) throws Exception {
        Fixture fixture = fixture();
        Request control = new Request(fixture.first(), "control_device", true);
        Request dynamic = new Request(fixture.second(), "dynamic_device", false);
        assertEmptyQuota(fixture);

        Results results = compete(fixture, controlFirst ? control : dynamic, controlFirst ? dynamic : control);

        assertThat(results.first().failure()).isNull();
        assertThat(results.first().value()).isNotNull();
        assertFailure(results.second(), DeviceErrorCode.DEVICE_QUOTA_EXCEEDED);
        JsonNode first = snapshot(fixture, fixture.first());
        JsonNode second = snapshot(fixture, fixture.second());
        assertThat(first.get("devices").size() + second.get("devices").size()).isEqualTo(1);
        assertThat(first.get("usage").get("tenant_used_value").asLong()).isEqualTo(1);
        assertThat(second.get("usage").get("tenant_used_value").asLong()).isEqualTo(1);
        assertThat(first.get("credentials")).isEmpty();
        assertThat(second.get("credentials")).hasSize(controlFirst ? 0 : 1);
        if (!controlFirst) assertCredential(second, results.first());
    }

    /** 相同key等待后必须读到已提交设备，即使上限已满也应返回冲突且不再生成任何令牌。 */
    @Test
    void concurrentSameKeyCreatesOneCredentialAndRejectsReplayWithoutResigning() throws Exception {
        Fixture fixture = fixture();
        Request request = new Request(fixture.first(), "shared_registration_key", false);
        assertEmptyQuota(fixture);

        Results results = compete(fixture, request, request);

        assertThat(results.first().failure()).isNull();
        assertFailure(results.second(), DeviceErrorCode.DEVICE_KEY_CONFLICT);
        JsonNode committed = snapshot(fixture, fixture.first());
        assertThat(committed.get("devices")).hasSize(1);
        assertThat(committed.get("credentials")).hasSize(1);
        assertThat(committed.get("usage").get("tenant_used_value").asLong()).isEqualTo(1);
        assertCredential(committed, results.first());
        assertThat(snapshot(fixture, fixture.second()).get("devices")).isEmpty();
        // 重试公开服务仍无HTTP身份，完整快照不变证明没有换token、更新时间或凭据版本。
        assertThat(TenantContext.current()).isEmpty();
        try {
            registrationService.register(request.project().key(), request.project().productKey(), PRODUCT_SECRET, request.key());
            throw new AssertionError("重复注册不得返回第二个令牌");
        } catch (BusinessException failure) {
            assertThat(failure.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_KEY_CONFLICT);
        }
        assertThat(snapshot(fixture, fixture.first())).isEqualTo(committed);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 成功令牌必须对应唯一已提交凭据，不能只检查接口成功而遗漏令牌和设备身份不一致。 */
    private void assertCredential(JsonNode state, Outcome outcome) throws Exception {
        assertThat(outcome.value()).isInstanceOf(DeviceRegistrationService.RegistrationResult.class);
        DeviceRegistrationService.RegistrationResult registered =
                (DeviceRegistrationService.RegistrationResult) outcome.value();
        JsonNode credential = state.get("credentials").get(0);
        assertThat(credential.get("device_id").asString()).isEqualTo(registered.deviceId().toString());
        assertThat(credential.get("credential_hash").asString()).isEqualTo(sha256(registered.accessToken()));
        assertThat(registered.accessToken()).hasSize(64);
    }

    /** 必须保留业务错误码，SQL唯一冲突或事务回滚异常不能伪装成预期拒绝。 */
    private void assertFailure(Outcome outcome, DeviceErrorCode expected) {
        assertThat(outcome.value()).isNull();
        assertThat(outcome.failure()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) outcome.failure()).errorCode()).isEqualTo(expected);
    }

    /** 闸门占真实生产锁，逐个观察排队后才释放；旧入口缺锁会提前结束并立即报错。 */
    private Results compete(Fixture fixture, Request firstRequest, Request secondRequest) throws Exception {
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try (Connection gate = appConnection()) {
            gate.setAutoCommit(false);
            try {
                int gatePid = Integer.parseInt(text(gate, "SELECT pg_backend_pid()"));
                text(gate, "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 735::bigint))",
                        fixture.tenantId().toString());
                CompletableFuture<Integer> firstPid = new CompletableFuture<>();
                Future<Outcome> first = submit(requests, fixture, firstRequest, firstPid);
                int actualFirstPid = firstPid.get(5, TimeUnit.SECONDS);
                assertAdvisoryLockWait(actualFirstPid, gatePid, first);
                CompletableFuture<Integer> secondPid = new CompletableFuture<>();
                Future<Outcome> second = submit(requests, fixture, secondRequest, secondPid);
                int actualSecondPid = secondPid.get(5, TimeUnit.SECONDS);
                assertThat(actualSecondPid).isNotEqualTo(actualFirstPid).isNotEqualTo(gatePid);
                assertAdvisoryLockWait(actualSecondPid, actualFirstPid, second);
                gate.rollback();
                return new Results(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                // 断言失败也先释放闸门；线程随后才能正常提交/回滚和退出。
                gate.rollback();
            }
        } finally {
            requests.shutdownNow();
            assertThat(requests.awaitTermination(25, TimeUnit.SECONDS)).as("并发注册工作线程有界退出").isTrue();
        }
    }

    /** 控制面有真实owner上下文，公开入口保持匿名；每个异常必须跨出事务边界后才能捕获。 */
    private Future<Outcome> submit(ExecutorService executor, Fixture fixture, Request request,
                                   CompletableFuture<Integer> pid) {
        return executor.submit(() -> {
            try {
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                if (request.control()) TenantContext.set(new TenantScope(fixture.tenantId(), request.project().id(), fixture.accountId()));
                Object result = new TransactionTemplate(transactionManager).execute(status -> {
                    jdbcTemplate.execute("SET LOCAL statement_timeout = '20s'");
                    jdbcTemplate.execute("SET LOCAL lock_timeout = '15s'");
                    assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                    pid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    if (request.control()) {
                        return deviceService.create(request.project().id(), null, request.key(), "配额竞争设备", null, null);
                    }
                    Object registered = registrationService.register(request.project().key(), request.project().productKey(),
                            PRODUCT_SECRET, request.key());
                    assertThat(TenantContext.current()).isEmpty();
                    assertThat(RlsScopeContext.current()).isEmpty();
                    return registered;
                });
                return new Outcome(result, null);
            } catch (Throwable failure) {
                if (!pid.isDone()) pid.completeExceptionally(failure);
                return new Outcome(null, failure);
            } finally {
                TenantContext.clear();
            }
        });
    }

    /** 同时检查未授予的advisory锁和阻塞PID，不能以线程启动或固定sleep假设发生了竞争。 */
    private void assertAdvisoryLockWait(int waitingPid, int blockingPid, Future<Outcome> request) throws Exception {
        try (Connection observer = appConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted)
                """)) {
            parameters(query, blockingPid, waitingPid, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    if (row.getBoolean(1) && row.getBoolean(2)) return;
                }
                if (request.isDone()) throw new AssertionError("注册请求未等待租户advisory锁便已结束", request.get().failure());
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到注册事务 " + waitingPid + " 在advisory锁队列等待 " + blockingPid);
    }

    /** 随机owner及其两个项目共享上限1；APP角色插入已发布类型及版本，注册仍经过真实产品确权。 */
    private Fixture fixture() throws Exception {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                projectFixture(), projectFixture());
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "INSERT INTO sys_quota_policy (id, code, device_count_limit) VALUES (?, ?, 1)",
                        fixture.policyId(), fixture.policyId().toString().replace("-", ""));
                execute(owner, "INSERT INTO sys_tenant (id, name, quota_policy_id) VALUES (?, '公开注册并发租户', ?)",
                        fixture.tenantId(), fixture.policyId());
                execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '并发owner')",
                        fixture.accountId(), fixture.accountId() + "@example.com");
                execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                        Uuid7.generate(), fixture.tenantId(), fixture.accountId());
                for (ProjectFixture project : List.of(fixture.first(), fixture.second())) {
                    execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '公开注册并发项目', ?)",
                            project.id(), fixture.tenantId(), project.key());
                    execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                            Uuid7.generate(), project.id(), fixture.accountId());
                }
                owner.commit();
            } finally {
                owner.rollback();
            }
        }
        for (ProjectFixture project : List.of(fixture.first(), fixture.second())) {
            try (Connection app = scopedConnection(fixture.tenantId(), project.id())) {
                try {
                    execute(app, """
                            INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                access_protocol, network_type, status, product_key, product_secret_hash)
                            VALUES (?, ?, ?, 'registration_race_type', '并发注册产品', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED', ?, ?)
                            """, project.typeId(), fixture.tenantId(), project.id(), project.productKey(), sha256(PRODUCT_SECRET));
                    execute(app, """
                            INSERT INTO dev_thing_model_version (id, tenant_id, project_id, device_type_id,
                                version_number, version_major, version_minor, version_patch, change_level,
                                schema_profile, model_snapshot, schema_digest, digest_algorithm)
                            VALUES (?, ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR', 'TC_PROPERTY_COMPOSITE_V1',
                                '{}'::jsonb, repeat('a', 64), 'PG_JSONB_TEXT_V1_SHA256')
                            """, Uuid7.generate(), fixture.tenantId(), project.id(), project.typeId());
                    app.commit();
                } finally {
                    app.rollback();
                }
            }
        }
        return fixture;
    }

    /** 每次生成新的公开标识，排除跨用例静态产品与配额缓存污染。 */
    private ProjectFixture projectFixture() {
        UUID projectId = Uuid7.generate();
        return new ProjectFixture(projectId, Uuid7.generate(), "race_" + projectId.toString().replace("-", ""),
                "product_" + projectId.toString().replace("-", ""));
    }

    /** 两个项目的真实投影均应从租户0/上限1起步，避免夹具自身已耗尽额度的假拒绝。 */
    private void assertEmptyQuota(Fixture fixture) throws SQLException {
        for (ProjectFixture project : List.of(fixture.first(), fixture.second())) {
            JsonNode state = snapshot(fixture, project);
            assertThat(state.get("limit").asLong()).isEqualTo(1);
            assertThat(state.get("usage").get("tenant_used_value").asLong()).isZero();
            assertThat(state.get("devices")).isEmpty();
            assertThat(state.get("credentials")).isEmpty();
        }
    }

    /** 独立APP连接读取已提交事实，完整凭据包括撤销项，防止新增后撤销掩盖第二次发放。 */
    private JsonNode snapshot(Fixture fixture, ProjectFixture project) throws SQLException {
        try (Connection connection = scopedConnection(fixture.tenantId(), project.id())) {
            return JSON.readTree(text(connection, """
                    SELECT jsonb_build_object(
                        'limit', (SELECT device_count_limit FROM sys_quota_policy WHERE id = ?),
                        'usage', (SELECT to_jsonb(u) FROM public.project_device_quota_usage() u),
                        'devices', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM dev_device d), '[]'::jsonb),
                        'credentials', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM dev_credential c), '[]'::jsonb))::text
                    """, fixture.policyId()));
        }
    }

    /** 摘要用于验证唯一一次发放的令牌与数据库事实相符，不能以明文写库满足断言。 */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 原生APP观察连接不占用应用池，同时验证角色，避免owner权限掩盖生产数据面问题。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        try {
            assertThat(text(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** APP种子和快照显式指定事务RLS范围；消息线程不复用这些连接或范围。 */
    private Connection scopedConnection(UUID tenantId, UUID projectId) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setAutoCommit(false);
            text(connection, "SELECT set_config('app.tenant_id', ?, true)", tenantId.toString());
            text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
            text(connection, "SELECT set_config('statement_timeout', '15s', true)");
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
    }

    /** 固定SQL模板使用参数绑定，写入事务的提交和回滚由上层显式管理。 */
    private void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            statement.executeUpdate();
        }
    }

    /** 单值查询必须实际返回一行，避免空作用域结果被当成零配额或空快照。 */
    private String text(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            parameters(statement, arguments);
            statement.setQueryTimeout(3);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** UUID、数字与字符串保留JDBC类型，不把业务数据拼入SQL。 */
    private void parameters(PreparedStatement statement, Object... arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
    }

    /** @param tenantId owner配额主体 @param policyId 本例独占策略 @param accountId 控制台owner账号
     * @param first 第一个真实项目 @param second 同owner第二个项目 */
    private record Fixture(UUID tenantId, UUID policyId, UUID accountId, ProjectFixture first, ProjectFixture second) { }

    /** @param id RLS项目主键 @param typeId 已发布产品类型 @param key 公开项目标识 @param productKey 产品标识 */
    private record ProjectFixture(UUID id, UUID typeId, String key, String productKey) { }

    /** @param project 请求归属 @param key 本次设备标识 @param control 是否控制面入口 */
    private record Request(ProjectFixture project, String key, boolean control) { }

    /** @param value 提交成功才返回的设备或注册令牌 @param failure 跨出事务回滚边界的原始异常 */
    private record Outcome(Object value, Throwable failure) { }

    /** @param first 先入队事务结果 @param second 后入队事务结果 */
    private record Results(Outcome first, Outcome second) { }
}
