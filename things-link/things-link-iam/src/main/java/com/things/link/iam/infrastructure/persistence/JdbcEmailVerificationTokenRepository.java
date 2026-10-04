package com.things.link.iam.infrastructure.persistence;

import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.domain.EmailVerificationToken;
import com.things.link.iam.domain.EmailVerificationTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的邮箱验证令牌仓储实现。
 */
@Repository
public class JdbcEmailVerificationTokenRepository implements EmailVerificationTokenRepository {

    private static final RowMapper<EmailVerificationToken> MAPPER = (rs, rowNum) -> new EmailVerificationToken(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            EmailVerificationPurpose.valueOf(rs.getString("purpose")),
            rs.getString("email"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("consumed_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("invalidated_at")).map(Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbcTemplate;

    public JdbcEmailVerificationTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(EmailVerificationToken token, byte[] tokenHash, String requestIp) {
        jdbcTemplate.update("""
                        INSERT INTO sys_email_verification_token
                            (id, account_id, purpose, email, token_hash, created_at, expires_at, request_ip)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::inet)
                        """,
                token.id(),
                token.accountId(),
                token.purpose().name(),
                token.email(),
                tokenHash,
                Timestamp.from(token.createdAt()),
                Timestamp.from(token.expiresAt()),
                // 转成 inet 由数据库校验格式。值来自请求头，可能是任意字符串 ——
                // 让数据库拒掉比在应用层写一个半吊子的 IP 校验可靠
                requestIp);
    }

    @Override
    public Optional<EmailVerificationToken> consume(byte[] tokenHash,
                                                    EmailVerificationPurpose purpose,
                                                    Instant now) {
        // UPDATE ... RETURNING 把「判断可用」与「标记已用」合成一条原子语句。
        //
        // consumed_at IS NULL 是一次性语义的**唯一**仲裁者：并发的两次点击里，
        // 只有一次能匹配到行，另一次影响 0 行、返回空。拆成先查后写必然漏。
        //
        // expires_at > now 用参数而不是数据库的 now()：测试要能控制时间，
        // 而且应用与数据库的时钟本就可能有偏差 —— 判过期的基准应当只有一个。
        return jdbcTemplate.query("""
                        UPDATE sys_email_verification_token
                           SET consumed_at = ?
                         WHERE token_hash = ?
                           AND purpose = ?
                           AND consumed_at IS NULL
                           AND invalidated_at IS NULL
                           AND expires_at > ?
                     RETURNING *
                        """,
                        MAPPER,
                        Timestamp.from(now), tokenHash, purpose.name(), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public int invalidateOutstanding(UUID accountId, EmailVerificationPurpose purpose, Instant now) {
        // 只动「还没用、也还没作废」的行。已消费的记录必须原样保留 ——
        // 排查账号异常时要能看出「这个重置链接确实在某时刻被用过」，
        // 把它改写成「已作废」等于抹掉那个事实。
        //
        // 不判 expires_at：已过期的行作废与否都不可用，多一个条件只会让
        // WHERE 与 V20260803_0400 里那条部分索引的条件对不上
        return jdbcTemplate.update("""
                UPDATE sys_email_verification_token
                   SET invalidated_at = ?
                 WHERE account_id = ?
                   AND purpose = ?
                   AND consumed_at IS NULL
                   AND invalidated_at IS NULL
                """, Timestamp.from(now), accountId, purpose.name());
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int maximumRows) {
        // 与刷新令牌使用相同的有界领取语义，避免两个安全事实表形成不同的运维行为。
        return jdbcTemplate.update("""
                DELETE FROM sys_email_verification_token
                 WHERE id IN (
                       SELECT id
                         FROM sys_email_verification_token
                        WHERE expires_at < ?
                        ORDER BY expires_at, id
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                 )
                """, Timestamp.from(cutoff), maximumRows);
    }

}
