package com.things.link.iam.infrastructure.persistence;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.TenantMembership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的账号仓储实现。
 *
 * <p>用 JdbcTemplate 而不是 JPA：认证路径上的查询都是简单的按键读取，
 * 引入实体管理与一级缓存只增加复杂度。业务聚合较复杂的模块再用 JPA。
 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            Account.Status.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getTimestamp("last_login_at")).map(Timestamp::toInstant).orElse(null),
            rs.getInt("failed_login_attempts"),
            Optional.ofNullable(rs.getTimestamp("locked_until")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("email_verified_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<TenantMembership> MEMBERSHIP_MAPPER = (rs, rowNum) -> new TenantMembership(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            TenantMembership.Status.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        // lower(email) 与迁移里的唯一索引表达式一致，因此这个查询能走该索引。
        // 写成 email = ? 会既不匹配大小写、又用不上索引
        return jdbcTemplate.query("""
                        SELECT * FROM sys_account
                         WHERE lower(email) = lower(?)
                           AND deleted_at IS NULL
                        """, ACCOUNT_MAPPER, email)
                .stream().findFirst();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbcTemplate.query(
                        "SELECT * FROM sys_account WHERE id = ? AND deleted_at IS NULL",
                        ACCOUNT_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Account> lockById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM sys_account WHERE id=? AND deleted_at IS NULL FOR SHARE",ACCOUNT_MAPPER,id)
                .stream().findFirst();
    }

    @Override
    public List<Account> findAllById(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            // 空集合拼出来是 IN ()，PostgreSQL 直接语法报错。
            // 而「没有成员的项目」是完全正常的状态，不该让它变成 500
            return List.of();
        }
        // ANY(?) 配数组参数，而不是拼 N 个问号：后者每种入参数量都是一条不同的 SQL，
        // 会把 PostgreSQL 的预编译语句缓存打散，也让慢查询日志无法聚合
        return jdbcTemplate.query(
                "SELECT * FROM sys_account WHERE id = ANY(?) AND deleted_at IS NULL",
                ps -> ps.setArray(1, ps.getConnection()
                        .createArrayOf("uuid", ids.toArray(UUID[]::new))),
                (java.sql.ResultSet rs) -> {
                    List<Account> accounts = new java.util.ArrayList<>();
                    int rowNum = 0;
                    while (rs.next()) {
                        accounts.add(ACCOUNT_MAPPER.mapRow(rs, rowNum++));
                    }
                    return accounts;
                });
    }

    @Override
    public List<TenantMembership> findMembershipsByAccount(UUID accountId) {
        return jdbcTemplate.query("""
                SELECT m.* FROM sys_tenant_member m
                  JOIN sys_tenant t ON t.id = m.tenant_id
                 WHERE m.account_id = ?
                   AND t.deleted_at IS NULL
                   AND t.status = 'ACTIVE'
                 ORDER BY m.created_at
                """, MEMBERSHIP_MAPPER, accountId);
    }

    @Override
    public void create(Account account) {
        // 不做「先查是否存在」——唯一索引 sys_account_email_uk 才是仲裁者。
        // 并发注册同一邮箱时，一个成功、另一个抛 DuplicateKeyException，
        // 由 application 层翻译成 20010
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """, account.id(), account.email(), account.passwordHash(), account.displayName());
    }

    @Override
    public void addMembership(UUID membershipId, UUID tenantId, UUID accountId) {
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, membershipId, tenantId, accountId);
    }

    @Override
    public void recordLogin(UUID accountId, Instant at) {
        jdbcTemplate.update("UPDATE sys_account SET last_login_at = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(at), accountId);
    }

    @Override
    public void recordFailedLogin(UUID accountId, int maxAttempts, Duration lockDuration, Instant now) {
        // 累加与判阈值在同一条语句里完成。
        //
        // 拆成「先 SELECT 次数、应用层判断、再 UPDATE」的话，并发的爆破请求会同时
        // 读到同一个次数、各自判断未达阈值，锁定永远不触发 —— 而爆破场景本身就是
        // 高并发的，正好是这个竞态最容易发生的时候。
        //
        // CASE 里的条件是「本次累加后是否达标」，所以用 failed_login_attempts + 1。
        // locked_until 的 WHEN 分支只在未处于锁定期时才写入新值，避免持续请求
        // 把锁定时间无限往后推（那会变成一个免费的拒绝服务通道）
        jdbcTemplate.update("""
                UPDATE sys_account
                   SET failed_login_attempts = failed_login_attempts + 1,
                       locked_until = CASE
                           WHEN failed_login_attempts + 1 >= ?
                                AND (locked_until IS NULL OR locked_until <= ?)
                           THEN ?
                           ELSE locked_until
                       END,
                       updated_at = now()
                 WHERE id = ?
                """,
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now.plus(lockDuration)),
                accountId);
    }

    @Override
    public boolean markEmailVerified(UUID accountId, String email, Instant verifiedAt) {
        // 两个条件都不能省：
        //
        // lower(email) = lower(?) 比对的是**令牌签发时的目标邮箱**。用户在等待验证
        // 期间又把邮箱改掉了（S4 的改邮箱流程），旧链接就不该再把新邮箱标记为已验证。
        //
        // email_verified_at IS NULL 让重复点击成为空操作，验证时刻保持为首次验证的
        // 时间 —— 与 revokeFamily 里 revoked_at IS NULL 是同一个理由：
        // 时间被刷新一遍之后，事后排查「何时验证的」就会看到错的答案。
        return jdbcTemplate.update("""
                UPDATE sys_account
                   SET email_verified_at = ?, updated_at = now()
                 WHERE id = ?
                   AND lower(email) = lower(?)
                   AND email_verified_at IS NULL
                   AND deleted_at IS NULL
                """, Timestamp.from(verifiedAt), accountId, email) == 1;
    }

    @Override
    public void updatePassword(UUID accountId, String passwordHash) {
        // 与清零锁定放在同一条语句里：拆成两条的话，中间失败会留下
        // 「口令已经换了、账号却还锁着」这种最难自助恢复的状态
        jdbcTemplate.update("""
                UPDATE sys_account
                   SET password_hash = ?,
                       failed_login_attempts = 0,
                       locked_until = NULL,
                       updated_at = now()
                 WHERE id = ?
                   AND deleted_at IS NULL
                """, passwordHash, accountId);
    }

    @Override
    public void resetFailedLogin(UUID accountId) {
        // 计数清零的同时把 locked_until 也清掉：记的是「连续」失败，
        // 一次成功登录就说明之前那串失败不再有意义
        jdbcTemplate.update("""
                UPDATE sys_account
                   SET failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                 WHERE id = ?
                   AND (failed_login_attempts <> 0 OR locked_until IS NOT NULL)
                """, accountId);
    }

}
