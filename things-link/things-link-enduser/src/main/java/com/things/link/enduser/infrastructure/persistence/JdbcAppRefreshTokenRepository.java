package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppRefreshToken;
import com.things.link.enduser.domain.AppRefreshTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的终端用户（App）刷新令牌仓储实现。
 *
 * <p>表 {@code app_refresh_token} 豁免 RLS（上下文建立类例外），因此本仓储的查询
 * <b>不依赖也不设置</b> RLS 上下文 —— 它只按 {@code token_hash} 命中。命中后的
 * 状态复核由应用服务在恢复租户/项目上下文后进行，本仓储只负责令牌这一半。
 */
@Repository
public class JdbcAppRefreshTokenRepository implements AppRefreshTokenRepository {

    private static final RowMapper<AppRefreshToken> MAPPER = (rs, rowNum) -> new AppRefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getLong("project_generation"),
            rs.getObject("family_id", UUID.class),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            rs.getObject("replaced_by", UUID.class),
            Optional.ofNullable(rs.getObject("session_group_id", UUID.class)).orElse(rs.getObject("family_id", UUID.class)));

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AppRefreshToken token, byte[] tokenHash) {
        jdbcTemplate.update("""
                        INSERT INTO app_refresh_token
                            (id, app_user_id, tenant_id, project_id, project_generation,
                             token_hash, family_id, issued_at, expires_at, session_group_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                token.id(), token.appUserId(), token.tenantId(), token.projectId(),
                token.projectGeneration(), tokenHash, token.familyId(),
                Timestamp.from(token.issuedAt()), Timestamp.from(token.expiresAt()), token.sessionGroupId());
    }

    @Override
    public Optional<AppRefreshToken> findByHash(byte[] tokenHash) {
        // 不加 revoked_at IS NULL 之类的条件：复用检测需要看到已失效的记录，
        // 过滤掉的话「被复用」和「不存在」就分不开了（见接口注释）
        return jdbcTemplate.query(
                        "SELECT * FROM app_refresh_token WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    /** ADR0097：用户锁是主互斥，条件更新防止异常写入悄悄覆盖轮换后继。 */
    @Override
    public int markRotated(UUID id, UUID replacedBy) {
        return jdbcTemplate.update("""
                UPDATE app_refresh_token SET replaced_by = ?
                 WHERE id = ? AND replaced_by IS NULL AND revoked_at IS NULL
                """, replacedBy, id);
    }

    @Override
    public int revokeFamily(UUID familyId, Instant revokedAt) {
        // revoked_at IS NULL 让重复撤销成为空操作，撤销时刻保持为首次撤销的时间。
        // 少了它的话，退出登录被点两次会把时间刷新一遍，事后排查「何时失效的」会看错
        return jdbcTemplate.update("""
                UPDATE app_refresh_token
                   SET revoked_at = ?
                 WHERE family_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), familyId);
    }

    @Override
    public int revokeAllForUser(UUID appUserId, Instant revokedAt) {
        // revoked_at IS NULL 同上：重复撤销是空操作，撤销时刻保持为首次撤销的时间
        return jdbcTemplate.update("""
                UPDATE app_refresh_token
                   SET revoked_at = ?
                 WHERE app_user_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), appUserId);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int maximumRows) {
        if (cutoff == null || maximumRows < 1 || maximumRows > 1_000) {
            throw new IllegalArgumentException("App token cleanup requires cutoff and batch in 1..1000");
        }
        // ADR0144：活跃族保留复用证据；只删无前驱的链头，避免跳锁后撞自引用外键。
        // 固定候选集，防止嵌套循环重复执行跳锁子查询而突破 maximumRows。
        return jdbcTemplate.update("""
                WITH candidates AS MATERIALIZED (
                       SELECT token.id
                         FROM app_refresh_token token
                        WHERE token.expires_at < ?
                          AND NOT EXISTS (
                              SELECT 1 FROM app_refresh_token retained
                               WHERE retained.family_id = token.family_id AND retained.expires_at >= ?)
                          AND NOT EXISTS (
                              SELECT 1 FROM app_refresh_token predecessor WHERE predecessor.replaced_by = token.id)
                        ORDER BY token.expires_at, token.id
                        LIMIT ?
                          FOR UPDATE OF token SKIP LOCKED
                 )
                DELETE FROM app_refresh_token WHERE id IN (SELECT id FROM candidates)
                """, Timestamp.from(cutoff), Timestamp.from(cutoff), maximumRows);
    }

}
