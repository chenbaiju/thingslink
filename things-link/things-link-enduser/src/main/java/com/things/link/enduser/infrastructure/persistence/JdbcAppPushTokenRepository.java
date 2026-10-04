package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.application.EncryptedPushToken;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.enduser.domain.AppPushTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * App 安装实例 PUSH token 的 JDBC 仓储。
 *
 * <p>表使用租户 RLS；每条 SQL 仍显式携带 tenant/appUser/installation 三轴，避免未来策略误配时扩大写入范围。
 * 密文只在写边界出现，查询领域对象时不投影敏感列（ADR 0051）。
 */
@Repository
public class JdbcAppPushTokenRepository implements AppPushTokenRepository {

    /** 非敏感安装实例元数据映射；故意不读取 token_cipher/token_nonce/key_id。 */
    private static final RowMapper<AppPushToken> TOKEN_MAPPER = (rs, rowNum) -> new AppPushToken(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            rs.getObject("installation_id", UUID.class),
            AppPushToken.Provider.valueOf(rs.getString("provider")),
            AppPushToken.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            toInstant(rs.getTimestamp("revoked_at")));

    /** 业务 JDBC 模板；连接由租户感知数据源提供。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 租户感知 JDBC 模板 */
    public JdbcAppPushTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>行不存在时 {@code FOR UPDATE} 无法加锁，因此用两个 int 的事务 advisory lock 建立串行点。
     * Java UUID hash 碰撞只会让无关注册短暂串行，不会放宽唯一性或隔离。
     */
    @Override
    public void lockRegistration(UUID tenantId, UUID appUserId, UUID installationId) {
        int firstKey = 31 * tenantId.hashCode() + appUserId.hashCode();
        int secondKey = installationId.hashCode();
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, ?)", resultSet -> null, firstKey, secondKey);
    }

    /** {@inheritDoc} */
    @Override
    public int insert(AppPushToken token, EncryptedPushToken encrypted) {
        return jdbcTemplate.update("""
                        INSERT INTO app_push_token
                            (id, tenant_id, app_user_id, installation_id, provider,
                             token_cipher, token_nonce, key_id, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        ON CONFLICT DO NOTHING
                        """,
                token.id(), token.tenantId(), token.appUserId(), token.installationId(), token.provider().name(),
                encrypted.cipherText(), encrypted.nonce(), encrypted.keyId(),
                Timestamp.from(token.createdAt()), Timestamp.from(token.updatedAt()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AppPushToken> findForUpdate(UUID tenantId, UUID appUserId, UUID installationId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, app_user_id, installation_id, provider, status,
                               created_at, updated_at, revoked_at
                          FROM app_push_token
                         WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?
                         FOR UPDATE
                        """, TOKEN_MAPPER, tenantId, appUserId, installationId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public int activate(AppPushToken token, EncryptedPushToken encrypted) {
        return jdbcTemplate.update("""
                        UPDATE app_push_token
                           SET provider = ?, token_cipher = ?, token_nonce = ?, key_id = ?,
                               status = 'ACTIVE', updated_at = ?, revoked_at = NULL
                         WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ? AND id = ?
                        """,
                token.provider().name(), encrypted.cipherText(), encrypted.nonce(), encrypted.keyId(),
                Timestamp.from(token.updatedAt()), token.tenantId(), token.appUserId(), token.installationId(), token.id());
    }

    /** {@inheritDoc} */
    @Override
    public int revoke(UUID tenantId, UUID appUserId, UUID installationId, Instant revokedAt) {
        return jdbcTemplate.update("""
                        UPDATE app_push_token
                           SET status = 'REVOKED', updated_at = ?, revoked_at = ?
                         WHERE tenant_id = ? AND app_user_id = ? AND installation_id = ?
                           AND status = 'ACTIVE'
                        """, Timestamp.from(revokedAt), Timestamp.from(revokedAt),
                tenantId, appUserId, installationId);
    }

    /** NULL 时间戳保持 NULL，不能伪造成 epoch。 */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
