package com.things.link.iam.infrastructure.persistence;

import com.things.link.iam.domain.RefreshToken;
import com.things.link.iam.domain.RefreshTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的刷新令牌仓储实现。
 */
@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final RowMapper<RefreshToken> MAPPER = (rs, rowNum) -> new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getLong("project_generation"),
            rs.getObject("family_id", UUID.class),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            rs.getObject("replaced_by", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(RefreshToken token, byte[] tokenHash, String userAgent, String clientIp) {
        jdbcTemplate.update("""
                        INSERT INTO sys_refresh_token
                            (id, account_id, tenant_id, project_id, project_generation, token_hash, family_id,
                             issued_at, expires_at, user_agent, client_ip)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet)
                        """,
                token.id(), token.accountId(), token.tenantId(), token.projectId(),
                token.projectLifecycleGeneration(), tokenHash, token.familyId(),
                Timestamp.from(token.issuedAt()), Timestamp.from(token.expiresAt()),
                userAgent,
                // 转成 inet 由数据库校验格式。传进来的值来自请求头，可能是任意字符串 ——
                // 让数据库拒掉比在应用层写一个半吊子的 IP 校验可靠
                clientIp);
    }

    @Override
    public Optional<RefreshToken> findByHash(byte[] tokenHash) {
        // 不加 revoked_at IS NULL 之类的条件：复用检测需要看到已失效的记录，
        // 过滤掉的话「被复用」和「不存在」就分不开了（见接口注释）
        return jdbcTemplate.query(
                        "SELECT * FROM sys_refresh_token WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    @Override
    public void markRotated(UUID id, UUID replacedBy) {
        jdbcTemplate.update(
                "UPDATE sys_refresh_token SET replaced_by = ? WHERE id = ?", replacedBy, id);
    }

    @Override
    public int revokeFamily(UUID familyId, Instant revokedAt) {
        // revoked_at IS NULL 让重复撤销成为空操作，撤销时刻保持为首次撤销的时间。
        // 少了它的话，退出登录被点两次会把时间刷新一遍，事后排查「何时失效的」会看错
        return jdbcTemplate.update("""
                UPDATE sys_refresh_token
                   SET revoked_at = ?
                 WHERE family_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), familyId);
    }

    @Override
    public int revokeAllForAccount(UUID accountId, Instant revokedAt) {
        // revoked_at IS NULL 同上：重复撤销是空操作，撤销时刻保持为首次撤销的时间
        return jdbcTemplate.update("""
                UPDATE sys_refresh_token
                   SET revoked_at = ?
                 WHERE account_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), accountId);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int maximumRows) {
        // SKIP LOCKED 允许多实例清理器安全竞争；按过期时间领取可避免旧行长期饥饿。
        return jdbcTemplate.update("""
                DELETE FROM sys_refresh_token
                 WHERE id IN (
                       SELECT id
                         FROM sys_refresh_token
                        WHERE expires_at < ?
                        ORDER BY expires_at, id
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                 )
                """, Timestamp.from(cutoff), maximumRows);
    }

}
