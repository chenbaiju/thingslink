package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.enduser.application.PushTokenCipher;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUSH 安装实例注册、轮换、吊销、密文与租户/用户隔离的真实 PostgreSQL API 资格（G2-A2a L2/L3）。
 *
 * <p>测试通过真实 App 登录、安全链、租户路由数据源、Flyway 表与 AES-GCM Bean，不以 H2 或 mock 仓储替代。
 */
@DisplayName("PUSH 安装实例 API（G2-A2a）")
@AutoConfigureMockMvc
class PushTokenApiTests extends AbstractIntegrationTest {

    /** 测试账号密码。 */
    private static final String PASSWORD = "secret123";

    /** HTTP 测试入口。 */
    @Autowired
    private MockMvc mockMvc;
    /** JSON 编解码器。 */
    @Autowired
    private ObjectMapper objectMapper;
    /** 真实租户感知 JDBC。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 与生产相同的口令编码器。 */
    @Autowired
    private PasswordEncoder passwordEncoder;
    /** 与生产相同的 AES-GCM 端口，用于权威回读验证。 */
    @Autowired
    private PushTokenCipher cipher;
    /** 真实事务服务，用于并发首次注册资格。 */
    @Autowired
    private AppPushTokenService pushTokenService;

    /** 当前租户。 */
    private UUID tenantId;
    /** 当前项目。 */
    private UUID projectId;
    /** 当前项目 key。 */
    private String projectKey;
    /** 主测试用户。 */
    private UUID aliceId;
    /** 同租户另一用户。 */
    private UUID bobId;

    /** 每例建立独立租户、项目、用户和项目角色。 */
    @BeforeEach
    void setUp() {
        tenantId = Uuid7.generate();
        projectId = Uuid7.generate();
        projectKey = "push" + projectId.toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "PUSH 测试租户");
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, 'PUSH 测试项目', 'sh-1', ?)
                """, projectId, tenantId, projectKey);
        aliceId = user("alice");
        bobId = user("bob");
        role(aliceId);
        role(bobId);
    }

    /** 按外键顺序清理，测试退出后不留下 token、会话或业务夹具。 */
    @AfterEach
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM app_refresh_token WHERE tenant_id = ?", tenantId);
            TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
            jdbcTemplate.update("DELETE FROM app_push_token WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
            TenantContext.clear();
            jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 注册不回显 token，数据库无明文；轮换复用 ID，吊销保留终态且幂等。 */
    @Test
    @DisplayName("注册、原位轮换、密文回读与幂等吊销")
    void registerRotateAndRevokeLifecycle() throws Exception {
        UUID installationId = UUID.randomUUID();
        String accessToken = login("alice");

        register(accessToken, installationId, "MOCK", "vendor-plain-one");
        PushRow first = row(aliceId, installationId);
        assertThat(first.status()).isEqualTo(AppPushToken.Status.ACTIVE);
        assertThat(first.provider()).isEqualTo(AppPushToken.Provider.MOCK);
        assertThat(first.nonce()).hasSize(12);
        assertThat(new String(first.cipherText(), StandardCharsets.UTF_8)).doesNotContain("vendor-plain-one");
        assertThat(decrypt(first)).isEqualTo("vendor-plain-one");

        register(accessToken, installationId, "MOCK", "vendor-plain-two");
        PushRow rotated = row(aliceId, installationId);
        assertThat(rotated.id()).isEqualTo(first.id());
        assertThat(rotated.createdAt()).isEqualTo(first.createdAt());
        assertThat(rotated.provider()).isEqualTo(AppPushToken.Provider.MOCK);
        assertThat(rotated.cipherText()).isNotEqualTo(first.cipherText());
        assertThat(decrypt(rotated)).isEqualTo("vendor-plain-two");
        assertThat(count(aliceId, installationId)).isOne();

        mockMvc.perform(delete("/api/v1/app/push-tokens/{installationId}", installationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/app/push-tokens/{installationId}", installationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        PushRow revoked = row(aliceId, installationId);
        assertThat(revoked.status()).isEqualTo(AppPushToken.Status.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
    }

    /** JWT subject 必须参与条件更新；同租户其他用户不能吊销该安装事实。 */
    @Test
    @DisplayName("其他 App 用户不能吊销当前用户安装实例")
    void revokeIsScopedToAuthenticatedAppUser() throws Exception {
        UUID installationId = UUID.randomUUID();
        register(login("alice"), installationId, "MOCK", "alice-secret");

        mockMvc.perform(delete("/api/v1/app/push-tokens/{installationId}", installationId)
                        .header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isNoContent());

        assertThat(row(aliceId, installationId).status()).isEqualTo(AppPushToken.Status.ACTIVE);
        assertThat(count(bobId, installationId)).isZero();
    }

    /** 空 token 在进入加密和数据库前由 Bean Validation 拒绝。 */
    @Test
    @DisplayName("空厂商 token 返回 400 且不产生事实")
    void blankTokenIsRejectedBeforePersistence() throws Exception {
        UUID installationId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/app/push-tokens")
                        .header("Authorization", "Bearer " + login("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "installationId", installationId,
                                "provider", "MOCK",
                                "token", " "))))
                .andExpect(status().isBadRequest());

        assertThat(count(aliceId, installationId)).isZero();
    }

    /** 请求完成后没有租户上下文时，tenant RLS 必须隐藏安装实例。 */
    @Test
    @DisplayName("app_push_token 无租户上下文时 fail-closed")
    void pushTokenTableIsTenantIsolated() throws Exception {
        UUID installationId = UUID.randomUUID();
        register(login("alice"), installationId, "MOCK", "isolated-secret");

        TenantContext.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_push_token", Integer.class)).isZero();
    }

    /** 不存在行没有普通行锁；事务 advisory lock 必须让并发首次注册收敛为一条可解密事实。 */
    @Test
    @DisplayName("并发首次注册只保留一条完整安装实例事实")
    void concurrentFirstRegistrationIsAtomic() throws Exception {
        UUID installationId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> concurrentRegister(
                    installationId, AppPushToken.Provider.MOCK, "concurrent-one", ready, start));
            Future<?> second = executor.submit(() -> concurrentRegister(
                    installationId, AppPushToken.Provider.MOCK, "concurrent-two", ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        PushRow stored = row(aliceId, installationId);
        assertThat(count(aliceId, installationId)).isOne();
        assertThat(decrypt(stored)).isIn("concurrent-one", "concurrent-two");
        assertThat(stored.provider()).isIn(AppPushToken.Provider.MOCK, AppPushToken.Provider.MOCK);
    }

    /** 执行注册并断言 204 空响应，禁止任何 token 形态回显。 */
    private void register(String accessToken, UUID installationId, String provider, String token) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/app/push-tokens")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "installationId", installationId,
                                "provider", provider,
                                "token", token))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    /** 并发App线程仅恢复RLS数据范围，可信项目显式传参，不能把App用户伪装为控制台actor。 */
    private void concurrentRegister(UUID installationId, AppPushToken.Provider provider, String token,
                                    CountDownLatch ready, CountDownLatch start) {
        RlsScopeContext.set(new RlsScope(tenantId, projectId));
        try {
            ready.countDown();
            start.await();
            pushTokenService.register(tenantId, projectId, aliceId, installationId, provider, token);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发注册被中断", exception);
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 在正确租户上下文读取一条权威数据库事实。 */
    private PushRow row(UUID appUserId, UUID installationId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, tenant_id, app_user_id, installation_id, provider, token_cipher,
                           token_nonce, key_id, status, created_at, updated_at, revoked_at
                      FROM app_push_token
                     WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?
                    """, (rs, rowNum) -> new PushRow(
                    rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("app_user_id", UUID.class), rs.getObject("installation_id", UUID.class),
                    AppPushToken.Provider.valueOf(rs.getString("provider")), rs.getBytes("token_cipher"),
                    rs.getBytes("token_nonce"), rs.getString("key_id"),
                    AppPushToken.Status.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    instant(rs.getTimestamp("revoked_at"))), tenantId, appUserId, installationId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 按相同 AAD 身份通过生产加密端口回读明文，证明信封可供后续发送边界恢复。 */
    private String decrypt(PushRow row) {
        AppPushToken metadata = new AppPushToken(
                row.id(), row.tenantId(), row.appUserId(), row.installationId(), row.provider(),
                row.status(), row.createdAt(), row.updatedAt(), row.revokedAt());
        return cipher.decrypt(metadata, new EncryptedPushToken(row.cipherText(), row.nonce(), row.keyId()));
    }

    /** 在正确租户上下文计数。 */
    private int count(UUID appUserId, UUID installationId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_push_token
                     WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?
                    """, Integer.class, tenantId, appUserId, installationId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 创建租户级 App 用户。 */
    private UUID user(String username) {
        UUID id = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, id, tenantId, username, passwordEncoder.encode(PASSWORD));
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    /** 创建有效项目角色以满足 App 登录门禁。 */
    private void role(UUID appUserId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                    VALUES (?, ?, ?, ?, 'OBSERVER')
                    """, Uuid7.generate(), tenantId, projectId, appUserId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 通过真实登录端点取得 App JWT。 */
    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "projectKey", projectKey,
                                "username", username,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** JSON 序列化失败属于测试基础设施失败，不能吞掉。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("测试 JSON 序列化失败", exception);
        }
    }

    /** 可空 JDBC 时间戳转换。 */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 数据库信封事实投影；仅测试进程内短暂持有敏感密文。 */
    private record PushRow(UUID id, UUID tenantId, UUID appUserId, UUID installationId,
                           AppPushToken.Provider provider, byte[] cipherText, byte[] nonce, String keyId,
                           AppPushToken.Status status, Instant createdAt, Instant updatedAt, Instant revokedAt) {
    }
}
