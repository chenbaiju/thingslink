package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppDeviceBindToken;
import com.things.link.enduser.domain.AppDeviceBindTokenRepository;
import com.things.link.enduser.domain.AppUserDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDBC 的一次性设备绑定令牌仓储。
 *
 * <p>{@code app_device_bind_token} 受项目 RLS 保护。查询仍显式携带 {@code project_id}，
 * 防止未来仓储被错误复用到提升权限连接时扩大读取范围；RLS 与 WHERE 各自独立防守。
 */
@Repository
public class JdbcAppDeviceBindTokenRepository implements AppDeviceBindTokenRepository {

    /**
     * 令牌事实映射器；不选择 {@code token_hash}，保证哈希不会进入领域对象。
     */
    private static final RowMapper<AppDeviceBindToken> MAPPER = (resultSet, rowNumber) ->
            new AppDeviceBindToken(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("project_id", UUID.class),
                    resultSet.getLong("project_generation"),
                    resultSet.getObject("device_id", UUID.class),
                    AppDeviceBindToken.Purpose.valueOf(resultSet.getString("purpose")),
                    AppUserDevice.RelationRole.valueOf(resultSet.getString("target_role")),
                    resultSet.getObject("issued_by_app_user_id", UUID.class),
                    resultSet.getTimestamp("expires_at").toInstant(),
                    resultSet.getInt("attempt_count"),
                    resultSet.getInt("max_attempts"),
                    nullableInstant(resultSet.getTimestamp("consumed_at")),
                    resultSet.getObject("consumed_by_app_user_id", UUID.class),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    /** JDBC 访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建绑定令牌仓储。
     *
     * @param jdbcTemplate JDBC 访问入口
     */
    public JdbcAppDeviceBindTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean save(AppDeviceBindToken token, byte[] tokenHash) {
        return jdbcTemplate.update("""
                        INSERT INTO app_device_bind_token
                            (id, tenant_id, project_id, project_generation, device_id,
                             token_hash, purpose, target_role,
                             issued_by_app_user_id,
                             expires_at, attempt_count, max_attempts, consumed_at,
                             consumed_by_app_user_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (token_hash) DO NOTHING
                        """,
                token.id(), token.tenantId(), token.projectId(), token.projectGeneration(),
                token.deviceId(), tokenHash,
                token.purpose().name(), token.targetRole().name(), token.issuedByAppUserId(),
                Timestamp.from(token.expiresAt()),
                token.attemptCount(), token.maxAttempts(), timestamp(token.consumedAt()),
                token.consumedByAppUserId(), Timestamp.from(token.createdAt()),
                Timestamp.from(token.updatedAt())) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppDeviceBindToken> findByProjectAndHash(UUID projectId, byte[] tokenHash) {
        // 不过滤 consumed_at/expires_at/attempt_count：后续状态机必须看见历史事实，
        // 否则重放会退化成“不存在”，无法形成确定的安全审计。
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, project_generation, device_id, purpose, target_role,
                               issued_by_app_user_id,
                               expires_at, attempt_count, max_attempts, consumed_at,
                               consumed_by_app_user_id, created_at, updated_at
                          FROM app_device_bind_token
                         WHERE project_id = ? AND token_hash = ?
                        """, MAPPER, projectId, tokenHash)
                .stream()
                .findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<AppDeviceBindToken> findByProjectAndHashForUpdate(UUID projectId, byte[] tokenHash) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, project_id, project_generation, device_id, purpose, target_role,
                               issued_by_app_user_id,
                               expires_at, attempt_count, max_attempts, consumed_at,
                               consumed_by_app_user_id, created_at, updated_at
                          FROM app_device_bind_token
                         WHERE project_id = ? AND token_hash = ?
                           FOR UPDATE
                        """, MAPPER, projectId, tokenHash)
                .stream()
                .findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int incrementAttempt(UUID projectId, UUID tokenId) {
        return jdbcTemplate.update("""
                        UPDATE app_device_bind_token
                           SET attempt_count = attempt_count + 1,
                               updated_at = now()
                         WHERE project_id = ? AND id = ?
                           AND attempt_count < max_attempts
                        """, projectId, tokenId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int consume(UUID projectId, UUID tokenId, UUID appUserId, Instant consumedAt) {
        return jdbcTemplate.update("""
                        UPDATE app_device_bind_token
                           SET consumed_at = ?, consumed_by_app_user_id = ?, updated_at = ?
                         WHERE project_id = ? AND id = ? AND consumed_at IS NULL
                        """, Timestamp.from(consumedAt), appUserId, Timestamp.from(consumedAt),
                projectId, tokenId);
    }

    /**
     * 把可空时刻转换为 JDBC 时间戳。
     *
     * @param instant 可空时刻
     * @return JDBC 时间戳或 {@code null}
     */
    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * 把可空 JDBC 时间戳转换为领域时刻。
     *
     * @param timestamp 可空 JDBC 时间戳
     * @return 领域时刻或 {@code null}
     */
    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
