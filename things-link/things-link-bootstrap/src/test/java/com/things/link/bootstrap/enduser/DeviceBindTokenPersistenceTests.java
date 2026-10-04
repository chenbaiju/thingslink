package com.things.link.bootstrap.enduser;

import com.things.link.enduser.domain.AppDeviceBindToken;
import com.things.link.enduser.domain.AppDeviceBindTokenRepository;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一次性设备绑定令牌持久化事实的真实 PostgreSQL 验收（G2-A1b～A1f）。
 *
 * <p>本类不测试尚未实现的签发/消费 API，只钉住本片承诺的四件事：数据库只保存
 * 32 字节哈希、purpose/target_role 组合由 CHECK 拒绝、尝试与消费半状态不能落库、
 * 项目 RLS 在真实非 owner 应用账号下隔离读取。
 */
@DisplayName("设备绑定令牌持久化事实（G2-A1b）")
class DeviceBindTokenPersistenceTests extends AbstractIntegrationTest {

    /** 真实 JDBC 仓储。 */
    @Autowired
    private AppDeviceBindTokenRepository repository;

    /** 用于准备跨表夹具与读取数据库约束事实。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类创建的项目及其租户，清理时用于恢复项目 RLS 上下文。 */
    private final Map<UUID, UUID> projectTenants = new LinkedHashMap<>();

    /**
     * 按外键逆序清理本类夹具；即使用例失败也不把令牌事实泄漏给共享容器中的后续测试。
     */
    @AfterEach
    void cleanup() {
        try {
            for (Map.Entry<UUID, UUID> entry : projectTenants.entrySet()) {
                withScope(entry.getValue(), entry.getKey(), () -> {
                    jdbcTemplate.update(
                            "DELETE FROM app_device_bind_token WHERE project_id = ?", entry.getKey());
                    jdbcTemplate.update(
                            "DELETE FROM app_user WHERE tenant_id = ?", entry.getValue());
                });
            }
        } finally {
            TenantContext.clear();
        }
        Set<UUID> tenantIds = new LinkedHashSet<>(projectTenants.values());
        for (UUID projectId : projectTenants.keySet()) {
            jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
        }
        for (UUID tenantId : tenantIds) {
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
        projectTenants.clear();
    }

    /** 保存后领域投影不含哈希，数据库也只留下固定长度摘要。 */
    @Test
    @DisplayName("仓储保存并读取令牌元数据，数据库只持有 SHA-256")
    void repositoryPersistsMetadataAndOnlyStoresHash() {
        UUID tenantId = tenant("令牌租户");
        UUID projectId = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] hash = sha256("claim-token-visible-once");
        AppDeviceBindToken token = token(
                tenantId, projectId, AppDeviceBindToken.Purpose.CLAIM,
                AppUserDevice.RelationRole.PRIMARY, createdAt, 3);

        withScope(tenantId, projectId, () -> repository.save(token, hash));

        Optional<AppDeviceBindToken> loaded = withScope(
                tenantId, projectId, () -> repository.findByProjectAndHash(projectId, hash));
        assertThat(loaded).contains(token);
        withScope(tenantId, projectId, () -> {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT octet_length(token_hash)
                      FROM app_device_bind_token
                     WHERE id = ?
                    """, Integer.class, token.id())).isEqualTo(32);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT token_hash = ?
                      FROM app_device_bind_token
                     WHERE id = ?
                    """, Boolean.class,
                    "claim-token-visible-once".getBytes(StandardCharsets.UTF_8), token.id()))
                    .isFalse();
        });
    }

    /** 同租户另一项目也不能按已知哈希读取本项目令牌。 */
    @Test
    @DisplayName("项目 RLS 隔离已知哈希，跨项目读取为空")
    void projectRlsHidesKnownHashFromAnotherProject() {
        UUID tenantId = tenant("隔离租户");
        UUID projectOne = project(tenantId);
        UUID projectTwo = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] hash = sha256("project-scoped-token");
        AppDeviceBindToken token = token(
                tenantId, projectOne, AppDeviceBindToken.Purpose.SHARE,
                AppUserDevice.RelationRole.READ_ONLY, createdAt, 2);

        withScope(tenantId, projectOne, () -> repository.save(token, hash));

        assertThat(withScope(tenantId, projectOne,
                () -> repository.findByProjectAndHash(projectOne, hash))).contains(token);
        assertThat(withScope(tenantId, projectTwo,
                () -> repository.findByProjectAndHash(projectOne, hash))).isEmpty();
    }

    /** purpose 与 target_role 的能力组合必须由数据库兜底，不能只靠未来 API 校验。 */
    @Test
    @DisplayName("数据库拒绝 CLAIM 授予 MEMBER 的非法能力组合")
    void databaseRejectsIllegalPurposeRoleCombination() {
        UUID tenantId = tenant("组合约束租户");
        UUID projectId = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AppDeviceBindToken invalid = token(
                tenantId, projectId, AppDeviceBindToken.Purpose.CLAIM,
                AppUserDevice.RelationRole.MEMBER, createdAt, 3);

        assertThatThrownBy(() -> withScope(tenantId, projectId,
                () -> repository.save(invalid, sha256("invalid-role-token"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("app_device_bind_token_purpose_target_role_check");
    }

    /** attempt_count 不得越过签发时冻结的 max_attempts。 */
    @Test
    @DisplayName("数据库拒绝尝试次数越过上限")
    void databaseRejectsAttemptCountBeyondMaximum() {
        UUID tenantId = tenant("尝试约束租户");
        UUID projectId = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AppDeviceBindToken invalid = new AppDeviceBindToken(
                Uuid7.generate(), tenantId, projectId, Uuid7.generate(),
                AppDeviceBindToken.Purpose.CLAIM, AppUserDevice.RelationRole.PRIMARY, null,
                createdAt.plus(10, ChronoUnit.MINUTES), 2, 1,
                null, null, createdAt, createdAt);

        assertThatThrownBy(() -> withScope(tenantId, projectId,
                () -> repository.save(invalid, sha256("too-many-attempts"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("app_device_bind_token_attempt_count_check");
    }

    /** 已消费时刻与消费人必须成对出现，避免无法归因的安全历史。 */
    @Test
    @DisplayName("数据库拒绝只有 consumed_at 的消费半状态")
    void databaseRejectsPartialConsumptionState() {
        UUID tenantId = tenant("消费约束租户");
        UUID projectId = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID issuerId = appUser(tenantId, projectId);
        AppDeviceBindToken invalid = new AppDeviceBindToken(
                Uuid7.generate(), tenantId, projectId, Uuid7.generate(),
                AppDeviceBindToken.Purpose.SHARE, AppUserDevice.RelationRole.MEMBER,
                issuerId,
                createdAt.plus(10, ChronoUnit.MINUTES), 1, 3,
                createdAt.plusSeconds(1), null, createdAt, createdAt.plusSeconds(1));

        assertThatThrownBy(() -> withScope(tenantId, projectId,
                () -> repository.save(invalid, sha256("partial-consumption"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("app_device_bind_token_consumption_check");
    }

    /** SHARE 必须记录签发人，使消费时能够拒绝已失去 PRIMARY 身份的旧邀请。 */
    @Test
    @DisplayName("数据库拒绝缺少签发人的 SHARE 令牌")
    void databaseRejectsShareTokenWithoutIssuer() {
        UUID tenantId = tenant("共享签发约束租户");
        UUID projectId = project(tenantId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AppDeviceBindToken invalid = new AppDeviceBindToken(
                Uuid7.generate(), tenantId, projectId, Uuid7.generate(),
                AppDeviceBindToken.Purpose.SHARE, AppUserDevice.RelationRole.READ_ONLY,
                null,
                createdAt.plus(10, ChronoUnit.MINUTES), 0, 3,
                null, null, createdAt, createdAt);

        assertThatThrownBy(() -> withScope(tenantId, projectId,
                () -> repository.save(invalid, sha256("share-without-issuer"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("app_device_bind_token_share_issuer_check");
    }

    /** 创建一个租户。 */
    private UUID tenant(String name) {
        UUID tenantId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }

    /** 创建项目并登记清理关系。 */
    private UUID project(UUID tenantId) {
        UUID projectId = Uuid7.generate();
        String projectKey = "bind" + projectId.toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "绑定令牌项目-" + projectId, projectKey);
        projectTenants.put(projectId, tenantId);
        return projectId;
    }

    /** 创建一条未消费令牌事实。 */
    private AppDeviceBindToken token(
            UUID tenantId,
            UUID projectId,
            AppDeviceBindToken.Purpose purpose,
            AppUserDevice.RelationRole targetRole,
            Instant createdAt,
            int maxAttempts) {
        UUID issuerId = purpose == AppDeviceBindToken.Purpose.SHARE
                ? appUser(tenantId, projectId)
                : null;
        return new AppDeviceBindToken(
                Uuid7.generate(), tenantId, projectId, Uuid7.generate(), purpose, targetRole,
                issuerId,
                createdAt.plus(10, ChronoUnit.MINUTES), 0, maxAttempts,
                null, null, createdAt, createdAt);
    }

    /** 创建 SHARE 外键要求的同租户签发人；凭据只用于数据库夹具且不可登录。 */
    private UUID appUser(UUID tenantId, UUID projectId) {
        UUID appUserId = Uuid7.generate();
        withScope(tenantId, projectId, () -> jdbcTemplate.update("""
                INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                VALUES (?, ?, ?, 'not-a-login-hash', 'ACTIVE')
                """, appUserId, tenantId, "issuer-" + appUserId));
        return appUserId;
    }

    /** 在指定项目 RLS 上下文中执行无返回值操作。 */
    private void withScope(UUID tenantId, UUID projectId, Runnable action) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在指定项目 RLS 上下文中执行有返回值操作。 */
    private <T> T withScope(UUID tenantId, UUID projectId, ScopedSupplier<T> supplier) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return supplier.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 计算令牌摘要；测试使用与方案 A 冻结一致的 SHA-256。 */
    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", exception);
        }
    }

    /** 避免引入额外函数式依赖的最小有返回值夹具接口。 */
    @FunctionalInterface
    private interface ScopedSupplier<T> {
        /** 执行项目范围内操作。 */
        T get();
    }
}
