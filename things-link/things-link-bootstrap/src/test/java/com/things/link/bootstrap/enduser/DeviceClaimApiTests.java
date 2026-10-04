package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppDeviceClaimRateLimiter;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CLAIM、解绑、独立主控转移与共享的真实 PostgreSQL/Redis API 合同（G2-A1c～A1f L2）。
 *
 * <p>覆盖控制台 OWNER 签发、App JWT 消费、只存 SHA-256、过期尝试、同用户幂等重放、
 * 关系冲突不消费、角色停用 fail-closed、两用户并发双花，以及自解绑/管理员解绑的 CLOSED
 * 历史、审计和即时授权失效；主控转移覆盖旧关系原位降 MEMBER、接收者 PRIMARY、签发者
 * 绑定、幂等和不同令牌并发仲裁；SHARE 覆盖 MEMBER/READ_ONLY、旧主控令牌、既有关系不改写与
 * 并发唯一关系。并发断言读取数据库权威事实，不以 HTTP 响应先后猜测事务结果。
 */
@DisplayName("设备 CLAIM、解绑、主控转移与共享状态机（G2-A1c～A1f）")
@AutoConfigureMockMvc
class DeviceClaimApiTests extends AbstractIntegrationTest {

    /** 控制台注册口令。 */
    private static final String CONSOLE_PASSWORD = "correct-horse-battery-staple";

    /** App 用户口令。 */
    private static final String APP_PASSWORD = "secret123";

    /** API 驱动。 */
    @Autowired
    private MockMvc mockMvc;

    /** JSON 序列化器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 权威数据库访问入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** App 用户口令哈希器。 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 控制台认证限流，测试之间清理。 */
    @Autowired
    private AuthRateLimiter authRateLimiter;

    /** App CLAIM 限流，测试之间清理。 */
    @Autowired
    private AppDeviceClaimRateLimiter claimRateLimiter;

    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 项目业务键，供 App 登录。 */
    private String projectKey;
    /** 控制台 OWNER access token。 */
    private String ownerToken;
    /** 可认领设备。 */
    private UUID deviceId;
    /** 第一 App 用户。 */
    private UUID aliceId;
    /** 第二 App 用户。 */
    private UUID bobId;
    /** 第一 App access token。 */
    private String aliceToken;
    /** 第二 App access token。 */
    private String bobToken;

    /** 每个用例建立独立租户、项目、设备与两个 ACTIVE App 用户。 */
    @BeforeEach
    void setUp() throws Exception {
        authRateLimiter.clear();
        claimRateLimiter.clear();
        String email = "claim-" + UUID.randomUUID() + "@example.com";
        ownerToken = registerAndLogin(email);
        projectId = createProject(ownerToken);
        tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class, projectId);
        projectKey = jdbcTemplate.queryForObject(
                "SELECT project_key FROM sys_project WHERE id = ?", String.class, projectId);

        withProjectScope(() -> {
            UUID typeId = Uuid7.generate();
            jdbcTemplate.update("""
                    INSERT INTO dev_type
                        (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                    VALUES (?, ?, ?, ?, 'CLAIM 类型', 'STANDARD', 'DIRECT', 'PUBLISHED')
                    """, typeId, tenantId, projectId, "claim-type-" + UUID.randomUUID());
            deviceId = Uuid7.generate();
            jdbcTemplate.update("""
                    INSERT INTO dev_device
                        (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, '待认领设备', 'OFFLINE')
                    """, deviceId, tenantId, projectId, typeId,
                    "claim-device-" + UUID.randomUUID());
        });

        aliceId = createAppUser("alice-" + UUID.randomUUID());
        bobId = createAppUser("bob-" + UUID.randomUUID());
        addAppRole(aliceId);
        addAppRole(bobId);
        aliceToken = loginApp(username(aliceId));
        bobToken = loginApp(username(bobId));
    }

    /** 按外键逆序清理本用例全部事实，不停止或删除用户容器。 */
    @AfterEach
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM app_refresh_token");
            if (tenantId != null && projectId != null) {
                withProjectScope(() -> {
                    jdbcTemplate.update("DELETE FROM app_device_bind_token WHERE project_id = ?", projectId);
                    jdbcTemplate.update("DELETE FROM app_user_device WHERE project_id = ?", projectId);
                    jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", projectId);
                    jdbcTemplate.update("DELETE FROM dev_device WHERE project_id = ?", projectId);
                    jdbcTemplate.update("DELETE FROM dev_type WHERE project_id = ?", projectId);
                });
                TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
                try {
                    jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
                } finally {
                    TenantContext.clear();
                }
            }
        } finally {
            TenantContext.clear();
        }
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
        authRateLimiter.clear();
        claimRateLimiter.clear();
    }

    /** 签发仅落哈希，消费在一个事务内同时提交 PRIMARY 与消费人。 */
    @Test
    void issueAndConsumePersistOnlyHashAndAtomicFacts() throws Exception {
        JsonNode issued = issue(deviceId);
        String plaintext = issued.get("token").asText();
        assertThat(plaintext).hasSize(43).doesNotContain("=");

        withProjectScope(() -> {
            byte[] stored = jdbcTemplate.queryForObject(
                    "SELECT token_hash FROM app_device_bind_token WHERE project_id = ?",
                    byte[].class, projectId);
            assertThat(stored).isEqualTo(sha256(plaintext));
            assertThat(new String(stored, StandardCharsets.UTF_8)).isNotEqualTo(plaintext);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT max_attempts FROM app_device_bind_token WHERE project_id = ?",
                    Integer.class, projectId)).isEqualTo(5);
        });

        MvcResult consumed = consume(aliceToken, plaintext);
        assertThat(consumed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(consumed.getResponse().getContentAsString());
        assertThat(body.get("deviceId").asText()).isEqualTo(deviceId.toString());
        assertThat(body.get("relationRole").asText()).isEqualTo("PRIMARY");

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND device_id = ? AND app_user_id = ?
                       AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                    """, Integer.class, projectId, deviceId, aliceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_by_app_user_id FROM app_device_bind_token WHERE project_id = ?
                    """, UUID.class, projectId)).isEqualTo(aliceId);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT attempt_count FROM app_device_bind_token WHERE project_id = ?
                    """, Integer.class, projectId)).isEqualTo(1);
        });
    }

    /** 过期令牌返回统一 60012，复核次数提交，但关系与消费事实均不产生。 */
    @Test
    void expiredTokenRecordsAttemptWithoutPartialConsumption() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        withProjectScope(() -> jdbcTemplate.update("""
                UPDATE app_device_bind_token
                   SET created_at = now() - interval '20 minutes',
                       expires_at = now() - interval '10 minutes'
                 WHERE project_id = ?
                """, projectId));

        MvcResult rejected = consume(aliceToken, plaintext);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(rejected)).isEqualTo(60012);

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT attempt_count FROM app_device_bind_token WHERE project_id = ?
                    """, Integer.class, projectId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_at IS NULL FROM app_device_bind_token WHERE project_id = ?
                    """, Boolean.class, projectId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device WHERE project_id = ?
                    """, Integer.class, projectId)).isZero();
        });
    }

    /** 响应丢失后的同用户重放返回同一 bindingId，不重复关系或尝试。 */
    @Test
    void sameConsumerReplayIsIdempotent() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        JsonNode first = body(consume(aliceToken, plaintext));
        JsonNode replay = body(consume(aliceToken, plaintext));

        assertThat(replay.get("bindingId").asText()).isEqualTo(first.get("bindingId").asText());
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device WHERE project_id = ? AND device_id = ?
                    """, Integer.class, projectId, deviceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT attempt_count FROM app_device_bind_token WHERE project_id = ?
                    """, Integer.class, projectId)).isEqualTo(1);
        });
    }

    /** 签发后出现其他 PRIMARY 时消费返回 60013，保留复核次数但不消费令牌。 */
    @Test
    void primaryConflictDoesNotConsumeToken() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        addPrimary(bobId);

        MvcResult rejected = consume(aliceToken, plaintext);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo(60013);
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT attempt_count FROM app_device_bind_token WHERE project_id = ?
                    """, Integer.class, projectId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_at IS NULL FROM app_device_bind_token WHERE project_id = ?
                    """, Boolean.class, projectId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device WHERE project_id = ? AND device_id = ?
                    """, Integer.class, projectId, deviceId)).isEqualTo(1);
        });
    }

    /** 角色停用后消费 fail-closed 为 60009，且不会烧掉令牌尝试。 */
    @Test
    void disabledProjectRoleDoesNotTouchToken() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        withProjectScope(() -> jdbcTemplate.update("""
                UPDATE app_user_role SET status = 'DISABLED'
                 WHERE project_id = ? AND app_user_id = ?
                """, projectId, aliceId));

        MvcResult rejected = consume(aliceToken, plaintext);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(rejected)).isEqualTo(60009);
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM app_device_bind_token WHERE project_id = ?
                """, Integer.class, projectId)).isZero());
    }

    /** 同一令牌两用户并发双花，数据库行锁保证只出现一个成功消费人与一个 PRIMARY。 */
    @Test
    void concurrentDoubleSpendHasSingleWinner() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> alice = executor.submit(() -> {
                start.await();
                return consume(aliceToken, plaintext);
            });
            Future<MvcResult> bob = executor.submit(() -> {
                start.await();
                return consume(bobToken, plaintext);
            });
            start.countDown();
            MvcResult aliceResult = alice.get();
            MvcResult bobResult = bob.get();

            int[] statuses = {
                    aliceResult.getResponse().getStatus(), bobResult.getResponse().getStatus()
            };
            Arrays.sort(statuses);
            assertThat(statuses).containsExactly(200, 400);
            MvcResult loser = aliceResult.getResponse().getStatus() == 400 ? aliceResult : bobResult;
            assertThat(code(loser)).isEqualTo(60012);
        }

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND device_id = ?
                       AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                    """, Integer.class, projectId, deviceId)).isEqualTo(1);
            UUID winner = jdbcTemplate.queryForObject("""
                    SELECT app_user_id FROM app_user_device
                     WHERE project_id = ? AND device_id = ? AND status = 'ACTIVE'
                    """, UUID.class, projectId, deviceId);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_by_app_user_id FROM app_device_bind_token WHERE project_id = ?
                    """, UUID.class, projectId)).isEqualTo(winner);
        });
    }

    /** 已有 PRIMARY 时控制台签发直接返回 60013，不制造新令牌事实。 */
    @Test
    void issueRejectsAlreadyClaimedDevice() throws Exception {
        addPrimary(bobId);

        MvcResult rejected = issueResult(deviceId);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo(60013);
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM app_device_bind_token WHERE project_id = ?
                """, Integer.class, projectId)).isZero());
    }

    /**
     * 自解绑以数据库时间更新历史并立即撤销访问；跨时钟的创建时间不决定解绑是否正确。
     *
     * @param offsetSeconds 创建时间相对数据库时钟的偏移，用过去和未来两例重现旧比较谓词的不可靠性
     */
    @ParameterizedTest
    @ValueSource(ints = {-60, 60})
    void selfUnbindClosesHistoryRevokesAccessAndIsIdempotent(int offsetSeconds) throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        JsonNode claimed = body(consume(aliceToken, plaintext));
        UUID bindingId = UUID.fromString(claimed.get("bindingId").asText());

        Instant createdAt = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Instant.class)
                .plusSeconds(offsetSeconds);
        // 显式模拟应用与数据库时钟偏差，避免测试结果由两台主机恰好同步决定。
        withProjectScope(() -> assertThat(jdbcTemplate.update("""
                UPDATE app_user_device SET created_at = ?, updated_at = ? WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt), bindingId)).isEqualTo(1));
        Instant beforeUnbind = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Instant.class);
        MvcResult first = unbindSelf(aliceToken);
        Instant afterUnbind = jdbcTemplate.queryForObject("SELECT clock_timestamp()", Instant.class);
        assertThat(first.getResponse().getStatus()).isEqualTo(204);
        Instant firstUpdatedAt = withProjectScopeResult(() -> jdbcTemplate.queryForObject("""
                SELECT updated_at FROM app_user_device WHERE id = ?
                """, Instant.class, bindingId));
        assertThat(firstUpdatedAt).isBetween(beforeUnbind, afterUnbind);

        MvcResult denied = mockMvc.perform(get("/api/v1/app/devices/{deviceId}", deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(denied)).isEqualTo(60010);

        MvcResult repeated = unbindSelf(aliceToken);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(204);
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT status FROM app_user_device WHERE id = ?
                    """, String.class, bindingId)).isEqualTo("CLOSED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT created_at FROM app_user_device WHERE id = ?
                    """, Instant.class, bindingId)).isEqualTo(createdAt);
            // 旧谓词仅作为反例：过去创建为 true，未来创建为 false，但两种偏差下解绑合同都成立。
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT created_at < updated_at FROM app_user_device WHERE id = ?
                    """, Boolean.class, bindingId)).isEqualTo(offsetSeconds < 0);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT updated_at FROM app_user_device WHERE id = ?
                    """, Instant.class, bindingId)).isEqualTo(firstUpdatedAt);
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type = 'app_user_device' AND target_id = ?
                   AND action = 'enduser.device.unbound.self'
                   AND actor_account_id IS NULL
                   AND details ->> 'actorType' = 'APP_USER'
                   AND details ->> 'actorId' = ?
                """, Integer.class, bindingId, aliceId.toString())).isEqualTo(1);
    }

    /** 控制台 OWNER 可解绑指定用户，审计 actor_account_id 必须是实际控制台账号。 */
    @Test
    void managerUnbindClosesRelationAndAuditsConsoleActor() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        UUID bindingId = UUID.fromString(body(consume(aliceToken, plaintext))
                .get("bindingId").asText());
        UUID ownerAccountId = jdbcTemplate.queryForObject("""
                SELECT account_id FROM sys_project_member
                 WHERE project_id = ? AND role = 'OWNER'
                """, UUID.class, projectId);

        MvcResult result = mockMvc.perform(delete(
                        "/api/v1/projects/{projectId}/end-users/{appUserId}/devices/{deviceId}",
                        projectId, aliceId, deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);

        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM app_user_device WHERE id = ?
                """, String.class, bindingId)).isEqualTo("CLOSED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type = 'app_user_device' AND target_id = ?
                   AND action = 'enduser.device.unbound.manager'
                   AND actor_account_id = ?
                   AND details ->> 'actorType' = 'ACCOUNT'
                """, Integer.class, bindingId, ownerAccountId)).isEqualTo(1);
    }

    /** App 角色停用后自解绑 fail-closed，不能改变仍有效的关系或补写成功审计。 */
    @Test
    void disabledRoleCannotSelfUnbind() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        UUID bindingId = UUID.fromString(body(consume(aliceToken, plaintext))
                .get("bindingId").asText());
        withProjectScope(() -> jdbcTemplate.update("""
                UPDATE app_user_role SET status = 'DISABLED'
                 WHERE project_id = ? AND app_user_id = ?
                """, projectId, aliceId));

        MvcResult denied = unbindSelf(aliceToken);
        assertThat(denied.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(denied)).isEqualTo(60009);
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM app_user_device WHERE id = ?
                """, String.class, bindingId)).isEqualTo("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log WHERE target_id = ?
                """, Integer.class, bindingId)).isZero();
    }

    /** 自解绑与管理员解绑并发时仅一方关闭 ACTIVE，最终只有一条 CLOSED 历史与一条审计。 */
    @Test
    void concurrentSelfAndManagerUnbindHaveSingleMutation() throws Exception {
        String plaintext = issue(deviceId).get("token").asText();
        UUID bindingId = UUID.fromString(body(consume(aliceToken, plaintext))
                .get("bindingId").asText());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> self = executor.submit(() -> {
                start.await();
                return unbindSelf(aliceToken);
            });
            Future<MvcResult> manager = executor.submit(() -> {
                start.await();
                return mockMvc.perform(delete(
                                "/api/v1/projects/{projectId}/end-users/{appUserId}/devices/{deviceId}",
                                projectId, aliceId, deviceId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                        .andReturn();
            });
            start.countDown();
            assertThat(self.get().getResponse().getStatus()).isEqualTo(204);
            assertThat(manager.get().getResponse().getStatus()).isEqualTo(204);
        }

        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM app_user_device
                 WHERE id = ? AND status = 'CLOSED'
                """, Integer.class, bindingId)).isEqualTo(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type = 'app_user_device' AND target_id = ?
                   AND action IN ('enduser.device.unbound.self', 'enduser.device.unbound.manager')
                """, Integer.class, bindingId)).isEqualTo(1);
    }

    /** 转移保持旧关系 ID 并降为 MEMBER，新主控唯一；同接收者重放不重复审计。 */
    @Test
    void transferDemotesPreviousPrimaryPromotesReceiverAndReplaysIdempotently() throws Exception {
        UUID previousBindingId = UUID.fromString(body(consume(
                aliceToken, issue(deviceId).get("token").asText())).get("bindingId").asText());
        JsonNode issued = issueTransfer(aliceToken);
        String plaintext = issued.get("token").asText();

        JsonNode transferred = body(consumeTransfer(bobToken, plaintext));
        JsonNode replay = body(consumeTransfer(bobToken, plaintext));
        assertThat(replay.get("bindingId").asText())
                .isEqualTo(transferred.get("bindingId").asText());

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT relation_role FROM app_user_device WHERE id = ?
                    """, String.class, previousBindingId)).isEqualTo("MEMBER");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND device_id = ?
                       AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                    """, Integer.class, projectId, deviceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT issued_by_app_user_id FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'TRANSFER'
                    """, UUID.class, projectId)).isEqualTo(aliceId);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_by_app_user_id FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'TRANSFER'
                    """, UUID.class, projectId)).isEqualTo(bobId);
        });
        UUID newBindingId = UUID.fromString(transferred.get("bindingId").asText());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE action = 'enduser.device.transfer.completed'
                   AND target_id = ?
                   AND details ->> 'previousPrimaryBindingId' = ?
                   AND details ->> 'newPrimaryAppUserId' = ?
                """, Integer.class, newBindingId, previousBindingId.toString(), bobId.toString()))
                .isEqualTo(1);
    }

    /** 非 PRIMARY 不能签发 TRANSFER，避免普通成员授予设备接管能力。 */
    @Test
    void nonPrimaryCannotIssueTransferToken() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());

        MvcResult rejected = issueTransferResult(bobToken);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(rejected)).isEqualTo(60010);
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM app_device_bind_token
                 WHERE project_id = ? AND purpose = 'TRANSFER'
                """, Integer.class, projectId)).isZero());
    }

    /** 接收者已有 MEMBER 时原位升级，bindingId 与 created_at 均不重写。 */
    @Test
    void transferPromotesExistingMemberInPlace() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        UUID memberBindingId = Uuid7.generate();
        Instant memberCreatedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        withProjectScope(() -> jdbcTemplate.update("""
                INSERT INTO app_user_device
                    (id, tenant_id, project_id, app_user_id, device_id,
                     relation_role, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'MEMBER', 'ACTIVE', ?, ?)
                """, memberBindingId, tenantId, projectId, bobId, deviceId,
                Timestamp.from(memberCreatedAt), Timestamp.from(memberCreatedAt)));

        JsonNode result = body(consumeTransfer(
                bobToken, issueTransfer(aliceToken).get("token").asText()));

        assertThat(result.get("bindingId").asText()).isEqualTo(memberBindingId.toString());
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT relation_role FROM app_user_device WHERE id = ?
                    """, String.class, memberBindingId)).isEqualTo("PRIMARY");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT created_at FROM app_user_device WHERE id = ?
                    """, Instant.class, memberBindingId)).isEqualTo(memberCreatedAt);
        });
    }

    /** 两个不同 TRANSFER 令牌并发消费也按设备 PRIMARY 行锁只产生一个赢家。 */
    @Test
    void concurrentTransferTokensHaveSingleWinnerAndNoOwnerlessWindow() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        String firstToken = issueTransfer(aliceToken).get("token").asText();
        String secondToken = issueTransfer(aliceToken).get("token").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return consumeTransfer(bobToken, firstToken);
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return consumeTransfer(bobToken, secondToken);
            });
            start.countDown();
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            int[] statuses = {
                    firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()
            };
            Arrays.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);
            MvcResult loser = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
            assertThat(code(loser)).isEqualTo(60017);
        }

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND device_id = ?
                       AND relation_role = 'PRIMARY' AND status = 'ACTIVE'
                    """, Integer.class, projectId, deviceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'TRANSFER' AND consumed_at IS NOT NULL
                    """, Integer.class, projectId)).isEqualTo(1);
        });
    }

    /** MEMBER 共享只存哈希并绑定签发者，消费与重放保持一条关系和一条完成审计。 */
    @Test
    void memberSharePersistsIssuerConsumesAndReplaysIdempotently() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        JsonNode issued = issueShare(aliceToken, "MEMBER");
        String plaintext = issued.get("token").asText();
        assertThat(issued.get("targetRole").asText()).isEqualTo("MEMBER");
        assertThat(plaintext).hasSize(43).doesNotContain("=");

        JsonNode shared = body(consumeShare(bobToken, plaintext));
        JsonNode replay = body(consumeShare(bobToken, plaintext));
        assertThat(replay.get("bindingId").asText()).isEqualTo(shared.get("bindingId").asText());
        UUID bindingId = UUID.fromString(shared.get("bindingId").asText());

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT relation_role FROM app_user_device WHERE id = ?
                    """, String.class, bindingId)).isEqualTo("MEMBER");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT issued_by_app_user_id FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE'
                    """, UUID.class, projectId)).isEqualTo(aliceId);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_by_app_user_id FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE'
                    """, UUID.class, projectId)).isEqualTo(bobId);
        });
        byte[] storedHash = withProjectScopeResult(() -> jdbcTemplate.queryForObject("""
                SELECT token_hash FROM app_device_bind_token
                 WHERE project_id = ? AND purpose = 'SHARE'
                """, byte[].class, projectId));
        assertThat(storedHash).containsExactly(sha256(plaintext));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE action = 'enduser.device.share.completed' AND target_id = ?
                """, Integer.class, bindingId)).isEqualTo(1);
    }

    /** READ_ONLY 必须按令牌目标角色落库，不能被默认提升为 MEMBER。 */
    @Test
    void readOnlySharePreservesLeastPrivilegeRole() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());

        JsonNode shared = body(consumeShare(
                bobToken, issueShare(aliceToken, "READ_ONLY").get("token").asText()));

        assertThat(shared.get("relationRole").asText()).isEqualTo("READ_ONLY");
        UUID bindingId = UUID.fromString(shared.get("bindingId").asText());
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT relation_role FROM app_user_device WHERE id = ?
                """, String.class, bindingId)).isEqualTo("READ_ONLY"));
    }

    /** 非 PRIMARY 不能签发 SHARE，且数据库不得留下旁路令牌。 */
    @Test
    void nonPrimaryCannotIssueShareToken() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());

        MvcResult rejected = issueShareResult(bobToken, "MEMBER");

        assertThat(rejected.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(rejected)).isEqualTo(60010);
        withProjectScope(() -> assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM app_device_bind_token
                 WHERE project_id = ? AND purpose = 'SHARE'
                """, Integer.class, projectId)).isZero());
    }

    /** 签发后主控变化使旧 SHARE 失效，接收者保持无关系且令牌不消费。 */
    @Test
    void shareTokenFromPreviousPrimaryCannotGrantAfterTransfer() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        String staleShare = issueShare(aliceToken, "MEMBER").get("token").asText();
        consumeTransfer(bobToken, issueTransfer(aliceToken).get("token").asText());
        UUID charlieId = createAppUser("charlie-" + UUID.randomUUID());
        addAppRole(charlieId);
        String charlieToken = loginApp(username(charlieId));

        MvcResult rejected = consumeShare(charlieToken, staleShare);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo(60020);
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE'
                    """, Integer.class, projectId, charlieId, deviceId)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_at IS NULL FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE'
                    """, Boolean.class, projectId)).isTrue();
        });
    }

    /** 已有 MEMBER 时 READ_ONLY SHARE 不得降权或消费令牌。 */
    @Test
    void shareDoesNotRewriteExistingRelationshipRole() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        UUID memberBindingId = Uuid7.generate();
        withProjectScope(() -> jdbcTemplate.update("""
                INSERT INTO app_user_device
                    (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                VALUES (?, ?, ?, ?, ?, 'MEMBER', 'ACTIVE')
                """, memberBindingId, tenantId, projectId, bobId, deviceId));
        String shareToken = issueShare(aliceToken, "READ_ONLY").get("token").asText();

        MvcResult rejected = consumeShare(bobToken, shareToken);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo(60020);
        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT relation_role FROM app_user_device WHERE id = ?
                    """, String.class, memberBindingId)).isEqualTo("MEMBER");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT consumed_at IS NULL FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE'
                    """, Boolean.class, projectId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT attempt_count FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE'
                    """, Integer.class, projectId)).isEqualTo(1);
        });
    }

    /** 两个 SHARE 令牌并发授予同一接收者时只能建立并消费一个。 */
    @Test
    void concurrentShareTokensCreateSingleRelationship() throws Exception {
        consume(aliceToken, issue(deviceId).get("token").asText());
        String firstToken = issueShare(aliceToken, "MEMBER").get("token").asText();
        String secondToken = issueShare(aliceToken, "READ_ONLY").get("token").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return consumeShare(bobToken, firstToken);
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return consumeShare(bobToken, secondToken);
            });
            start.countDown();
            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            int[] statuses = {
                    firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()
            };
            Arrays.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);
            MvcResult loser = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
            assertThat(code(loser)).isEqualTo(60020);
        }

        withProjectScope(() -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_user_device
                     WHERE project_id = ? AND app_user_id = ? AND device_id = ? AND status = 'ACTIVE'
                    """, Integer.class, projectId, bobId, deviceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM app_device_bind_token
                     WHERE project_id = ? AND purpose = 'SHARE' AND consumed_at IS NOT NULL
                    """, Integer.class, projectId)).isEqualTo(1);
        });
    }

    /** 注册控制台 OWNER 并取得真实 access token。 */
    private String registerAndLogin(String email) throws Exception {
        authRateLimiter.clear();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", CONSOLE_PASSWORD))))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", CONSOLE_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return body(login).get("accessToken").asText();
    }

    /** 用真实控制台 API 创建项目。 */
    private UUID createProject(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("name", "CLAIM 项目", "region", "sh-1"))))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    /** 创建租户级 App 用户并返回 ID。 */
    private UUID createAppUser(String username) {
        UUID id = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, id, tenantId, username, passwordEncoder.encode(APP_PASSWORD));
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    /** 为 App 用户建立 ACTIVE 项目角色。 */
    private void addAppRole(UUID appUserId) {
        withProjectScope(() -> jdbcTemplate.update("""
                INSERT INTO app_user_role
                    (id, tenant_id, project_id, app_user_id, role, status)
                VALUES (?, ?, ?, ?, 'APP_ADMIN', 'ACTIVE')
                """, Uuid7.generate(), tenantId, projectId, appUserId));
    }

    /** 按用户 ID 读取夹具用户名。 */
    private String username(UUID appUserId) {
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username FROM app_user WHERE id = ?", String.class, appUserId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 使用真实 App 登录端点，同一项目夹具共享来源地址，避免其他夹具耗尽默认 IP 配额。 */
    private String loginApp(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(fixtureClientIp());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "projectKey", projectKey,
                                "username", username,
                                "password", APP_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("accessToken").asText();
    }

    /** 将项目 UUID 低 96 位映射到文档 IPv6 网段，保留真实 IP 限流而隔离不同夹具。 */
    private String fixtureClientIp() {
        String suffix = projectId.toString().replace("-", "").substring(8);
        return "2001:db8:" + suffix.substring(0, 4) + ":" + suffix.substring(4, 8)
                + ":" + suffix.substring(8, 12) + ":" + suffix.substring(12, 16)
                + ":" + suffix.substring(16, 20) + ":" + suffix.substring(20, 24);
    }

    /** 调用控制台签发端点并要求成功。 */
    private JsonNode issue(UUID targetDeviceId) throws Exception {
        MvcResult result = issueResult(targetDeviceId);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    /** 调用控制台签发端点，保留任意状态供负例断言。 */
    private MvcResult issueResult(UUID targetDeviceId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/{projectId}/end-users/device-claim-tokens", projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("deviceId", targetDeviceId))))
                .andReturn();
    }

    /** 调用 App CLAIM 消费端点。 */
    private MvcResult consume(String appToken, String plaintext) throws Exception {
        return mockMvc.perform(post("/api/v1/app/device-claims")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", plaintext))))
                .andReturn();
    }

    /** 当前 App PRIMARY 签发一次性 TRANSFER 令牌并要求成功。 */
    private JsonNode issueTransfer(String appToken) throws Exception {
        MvcResult result = issueTransferResult(appToken);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    /** 调用 TRANSFER 签发端点，保留任意状态供负例断言。 */
    private MvcResult issueTransferResult(String appToken) throws Exception {
        return mockMvc.perform(post("/api/v1/app/devices/{deviceId}/transfer-tokens", deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken))
                .andReturn();
    }

    /** 调用 App TRANSFER 消费端点。 */
    private MvcResult consumeTransfer(String appToken, String plaintext) throws Exception {
        return mockMvc.perform(post("/api/v1/app/device-transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", plaintext))))
                .andReturn();
    }

    /** 当前 App PRIMARY 签发一次性 SHARE 令牌并要求成功。 */
    private JsonNode issueShare(String appToken, String targetRole) throws Exception {
        MvcResult result = issueShareResult(appToken, targetRole);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    /** 调用 SHARE 签发端点，保留任意状态供负例断言。 */
    private MvcResult issueShareResult(String appToken, String targetRole) throws Exception {
        return mockMvc.perform(post("/api/v1/app/devices/{deviceId}/share-tokens", deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("targetRole", targetRole))))
                .andReturn();
    }

    /** 调用 App SHARE 消费端点。 */
    private MvcResult consumeShare(String appToken, String plaintext) throws Exception {
        return mockMvc.perform(post("/api/v1/app/device-shares")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", plaintext))))
                .andReturn();
    }

    /** 调用 App 自解绑端点，保留任意状态供正负例断言。 */
    private MvcResult unbindSelf(String appToken) throws Exception {
        return mockMvc.perform(delete("/api/v1/app/devices/{deviceId}/binding", deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken))
                .andReturn();
    }

    /** 直接建立一个有效 PRIMARY，用于签发/消费冲突夹具。 */
    private void addPrimary(UUID appUserId) {
        withProjectScope(() -> jdbcTemplate.update("""
                INSERT INTO app_user_device
                    (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                VALUES (?, ?, ?, ?, ?, 'PRIMARY', 'ACTIVE')
                """, Uuid7.generate(), tenantId, projectId, appUserId, deviceId));
    }

    /** 在本测试项目的双轴 RLS 上下文内执行动作。 */
    private void withProjectScope(Runnable action) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在本项目双轴内读取一个数据库结果并保证清理 ThreadLocal。 */
    private <T> T withProjectScopeResult(java.util.function.Supplier<T> action) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 读取统一 JSON 响应。 */
    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 提取统一业务错误码。 */
    private int code(MvcResult result) throws Exception {
        return body(result).get("code").asInt();
    }

    /** JSON 序列化。 */
    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** 复算令牌 SHA-256，独立证明数据库没有保存明文。 */
    private static byte[] sha256(String plaintext) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行时缺少 SHA-256", exception);
        }
    }
}
