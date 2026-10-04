package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的终端用户仓储实现。
 *
 * <p>app_user 受租户 RLS 保护：本仓储<b>不自行设置租户上下文</b>，上下文由应用服务在
 * 写入前切到项目归属租户（{@code set_config('app.tenant_id', ...)}）。仓储层的查询显式带
 * {@code tenant_id = ?}，与 RLS 形成两道独立防线。
 */
@Repository
public class JdbcAppUserRepository implements AppUserRepository {

    private static final RowMapper<AppUser> USER_MAPPER = (rs, rowNum) -> new AppUser(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            AppUser.Status.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("password_changed_at")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void lockTenantCapacity(UUID tenantId) {
        requireSessionTransaction(tenantId);
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 12041))",
                rs -> { }, "tenant-end-user-capacity-v1:" + tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public long countByTenant(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE tenant_id=?",
                Long.class, tenantId);
    }

    @Override
    public void create(AppUser user) {
        jdbcTemplate.update("""
                        INSERT INTO app_user (id, tenant_id, username, password_hash, display_name, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                user.id(), user.tenantId(), user.username(), user.passwordHash(),
                user.displayName(), user.status().name());
    }

    @Override
    public Optional<AppUser> findByTenantAndUsername(UUID tenantId, String username) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, username, password_hash, display_name, status,
                               password_changed_at, created_at
                          FROM app_user
                         WHERE tenant_id = ? AND username = ?
                        """, USER_MAPPER, tenantId, username).stream().findFirst();
    }

    @Override
    public Optional<AppUser> findByIdAndTenant(UUID tenantId, UUID userId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, username, password_hash, display_name, status,
                               password_changed_at, created_at
                          FROM app_user
                         WHERE tenant_id = ? AND id = ?
                        """, USER_MAPPER, tenantId, userId).stream().findFirst();
    }

    /** ADR0097：验密读取与会话互斥是同一次锁后查询，避免先验证旧密码再等待改密提交。 */
    @Override
    public Optional<AppUser> lockByTenantAndUsername(UUID tenantId, String username) {
        requireSessionTransaction(tenantId);
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, username, password_hash, display_name, status,
                               password_changed_at, created_at
                          FROM app_user WHERE tenant_id = ? AND username = ?
                           FOR NO KEY UPDATE
                        """, USER_MAPPER, tenantId, username).stream().findFirst();
    }

    /** ADR0097：共享用户不会随项目purge删除，故其行锁比可过期清理的族根令牌稳定。 */
    @Override
    public Optional<AppUser> lockByIdAndTenant(UUID tenantId, UUID userId) {
        requireSessionTransaction(tenantId);
        if (userId == null) {
            throw new IllegalArgumentException("会话锁必须提供可信用户身份");
        }
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, username, password_hash, display_name, status,
                               password_changed_at, created_at
                          FROM app_user WHERE tenant_id = ? AND id = ?
                           FOR NO KEY UPDATE
                        """, USER_MAPPER, tenantId, userId).stream().findFirst();
    }

    /** ADR0097：锁后重读依赖新RC快照；拒绝自动提交、只读或更强快照隔离，不假装等待即见新事实。 */
    private void requireSessionTransaction(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("会话锁必须提供可信租户身份");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("用户会话锁必须加入已有非只读事务");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            int isolation = connection.getTransactionIsolation();
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || (isolation != Connection.TRANSACTION_READ_COMMITTED
                    && isolation != Connection.TRANSACTION_READ_UNCOMMITTED)) {
                throw new IllegalStateException("用户会话锁要求非只读READ COMMITTED原事务");
            }
            return null;
        });
    }

    @Override
    public int updatePassword(UUID tenantId, UUID appUserId, String passwordHash, Instant passwordChangedAt) {
        // 显式 tenant_id 条件与租户 RLS 是两道独立防线：前者兜底「RLS 误配」，后者兜底
        // 「这里忘了带租户」。缺了显式条件，跨租户改密会被 RLS 拦下却报「0 行」而非「越权」。
        return jdbcTemplate.update("""
                        UPDATE app_user
                           SET password_hash = ?, password_changed_at = ?
                         WHERE tenant_id = ? AND id = ?
                        """,
                passwordHash, Timestamp.from(passwordChangedAt), tenantId, appUserId);
    }

    /** {@code password_changed_at} 可空；NULL 表示账号自创建后未改过密码。 */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
