package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.DashboardShareCreationResult;
import com.things.link.dashboard.domain.DashboardShareRepository;
import com.things.link.dashboard.domain.DashboardShareSummary;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADR0101分享管理普通RLS持久适配；项目和Dashboard锁由调用方先取得。
 * 分享、全部有界scope及幂等恢复映射必须加入同一原事务，secret正文始终不进入SQL。
 */
@Repository
public class JdbcDashboardShareRepository implements DashboardShareRepository {
    /** 仅映射已经验证并存为JSONB的HostRange，不记录正文或摘要。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 加入服务原事务的普通运行身份连接。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 服从可信RLS和事务连接的JDBC入口 */
    public JdbcDashboardShareRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@inheritDoc} */
    @Override
    public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DashboardShareCreationResult> findCreationResult(UUID tenantId, UUID projectId, UUID dashboardId,
            UUID accountId, String idempotencyKeyDigest) {
        return jdbc.query("""
                SELECT tenant_id, project_id, dashboard_id, account_id, idempotency_key_digest,
                       request_digest, share_id, created_at
                  FROM dash_share_creation_result
                 WHERE tenant_id = ? AND project_id = ? AND dashboard_id = ?
                   AND account_id = ? AND idempotency_key_digest = ?
                """, (result, row) -> new DashboardShareCreationResult(
                result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                result.getObject("dashboard_id", UUID.class), result.getObject("account_id", UUID.class),
                result.getString("idempotency_key_digest"), result.getString("request_digest"),
                result.getObject("share_id", UUID.class), result.getTimestamp("created_at").toInstant()),
                tenantId, projectId, dashboardId, accountId, idempotencyKeyDigest).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public long countActive(UUID projectId, UUID dashboardId, Instant now) {
        // 只需区分0..20与超限；最多读取21行，不把计数变成历史全量加载。
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT id FROM dash_share_token
                     WHERE project_id = ? AND dashboard_id = ? AND revoked_at IS NULL AND expires_at > ?
                     LIMIT 21
                ) active
                """, Long.class, projectId, dashboardId, Timestamp.from(now));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(DashboardShareToken token, List<DashboardShareVariableScope> scopes,
            DashboardShareCreationResult creation) {
        requireWriteTransaction();
        validateAggregate(token, scopes, creation);
        jdbc.update("""
                INSERT INTO dash_share_token(id, tenant_id, project_id, dashboard_id, dashboard_version_id,
                    project_generation, secret_hash, host_compatibility, referer_policy, created_at, expires_at,
                    creator_account_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, token.id(), token.tenantId(), token.projectId(), token.dashboardId(), token.dashboardVersionId(),
                token.projectGeneration(), token.secretHash(), token.hostCompatibility().toString(), token.refererPolicy(),
                Timestamp.from(token.createdAt()), Timestamp.from(token.expiresAt()), token.creatorAccountId());
        for (int position = 0; position < scopes.size(); position++) {
            DashboardShareVariableScope scope = scopes.get(position);
            jdbc.update("""
                    INSERT INTO dash_share_scope(tenant_id, project_id, dashboard_id, dashboard_version_id,
                        share_id, variable_key, position, thing_model_version_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, token.tenantId(), token.projectId(), token.dashboardId(), token.dashboardVersionId(),
                    token.id(), scope.variableKey(), position, scope.modelVersionId());
            for (int index = 0; index < scope.deviceIds().size(); index++) {
                jdbc.update("""
                        INSERT INTO dash_share_scope_device(tenant_id, project_id, dashboard_id, dashboard_version_id,
                            share_id, variable_key, device_id, thing_model_version_id, position)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, token.tenantId(), token.projectId(), token.dashboardId(), token.dashboardVersionId(),
                        token.id(), scope.variableKey(), scope.deviceIds().get(index), scope.modelVersionId(), index);
            }
        }
        jdbc.update("""
                INSERT INTO dash_share_creation_result(tenant_id, project_id, dashboard_id, account_id,
                    idempotency_key_digest, request_digest, share_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, creation.tenantId(), creation.projectId(), creation.dashboardId(), creation.accountId(),
                creation.idempotencyKeyDigest(), creation.requestDigest(), creation.shareId(), Timestamp.from(creation.createdAt()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DashboardShareToken> find(UUID projectId, UUID dashboardId, UUID shareId) {
        return jdbc.query("SELECT * FROM dash_share_token WHERE project_id = ? AND dashboard_id = ? AND id = ?",
                JdbcDashboardShareRepository::token, projectId, dashboardId, shareId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public List<DashboardShareSummary> page(UUID projectId, UUID dashboardId, Instant beforeAt, UUID beforeId, int fetchLimit) {
        if ((beforeAt == null) != (beforeId == null) || fetchLimit < 1 || fetchLimit > 51) {
            throw new IllegalArgumentException("分享管理keyset或页上限非法");
        }
        return jdbc.query("""
                SELECT token.id, token.dashboard_version_id, version.version_number, token.referer_policy,
                       token.host_compatibility::text, token.expires_at, token.created_at, token.revoked_at,
                       CASE WHEN token.revoked_at IS NOT NULL THEN 'REVOKED'
                            WHEN token.expires_at <= statement_timestamp() THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
                  FROM dash_share_token token
                  JOIN dash_dashboard_version version ON version.tenant_id = token.tenant_id
                   AND version.project_id = token.project_id AND version.dashboard_id = token.dashboard_id
                   AND version.id = token.dashboard_version_id
                 WHERE token.project_id = ? AND token.dashboard_id = ?
                   AND (?::timestamptz IS NULL OR (token.created_at, token.id) < (?::timestamptz, ?::uuid))
                 ORDER BY token.created_at DESC, token.id DESC LIMIT ?
                """, (result, row) -> new DashboardShareSummary(result.getObject("id", UUID.class),
                result.getObject("dashboard_version_id", UUID.class), result.getLong("version_number"),
                result.getString("status"), result.getString("referer_policy"), JSON.readTree(result.getString("host_compatibility")),
                result.getTimestamp("expires_at").toInstant(), result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("revoked_at"))), projectId, dashboardId,
                beforeAt == null ? null : Timestamp.from(beforeAt), beforeAt == null ? null : Timestamp.from(beforeAt),
                beforeId, fetchLimit);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DashboardShareToken> revoke(UUID projectId, UUID dashboardId, UUID shareId, UUID accountId) {
        requireWriteTransaction();
        return jdbc.query("""
                UPDATE dash_share_token SET revoked_at = clock_timestamp(), revoked_by = ?
                 WHERE project_id = ? AND dashboard_id = ? AND id = ? AND revoked_at IS NULL
                RETURNING *
                """, JdbcDashboardShareRepository::token, accountId, projectId, dashboardId, shareId).stream().findFirst();
    }

    /** 精确SQL返回完整内部事实，公开列表使用独立摘要投影防止hash泄露。 */
    private static DashboardShareToken token(ResultSet result, int row) throws SQLException {
        return new DashboardShareToken(result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                result.getObject("project_id", UUID.class), result.getObject("dashboard_id", UUID.class),
                result.getObject("dashboard_version_id", UUID.class), result.getLong("project_generation"),
                result.getString("secret_hash"), JSON.readTree(result.getString("host_compatibility")),
                result.getString("referer_policy"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(), result.getObject("creator_account_id", UUID.class),
                instant(result.getTimestamp("revoked_at")), result.getObject("revoked_by", UUID.class));
    }

    /** 可空撤销时间保持数据库空值语义，不合成当前时刻。 */
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    /** 拒绝拆开token、scope和结果映射的自动提交，也拒绝只读外事务。 */
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("分享写入必须加入已有非只读事务");
        }
    }

    /** 复合FK之外先检查聚合绑定与整个候选并集，不能按每变量20项放大成400设备。 */
    private static void validateAggregate(DashboardShareToken token, List<DashboardShareVariableScope> scopes,
            DashboardShareCreationResult creation) {
        if (!token.id().equals(creation.shareId()) || !token.tenantId().equals(creation.tenantId())
                || !token.projectId().equals(creation.projectId()) || !token.dashboardId().equals(creation.dashboardId())
                || !token.creatorAccountId().equals(creation.accountId()) || !token.createdAt().equals(creation.createdAt())
                || token.revokedAt() != null || token.revokedBy() != null || scopes == null || scopes.size() > 20) {
            throw new IllegalArgumentException("分享聚合身份或初始状态不一致");
        }
        Set<String> variables = new HashSet<>();
        Set<UUID> devices = new HashSet<>();
        for (DashboardShareVariableScope scope : scopes) {
            if (scope == null || scope.variableKey() == null || !variables.add(scope.variableKey())
                    || scope.modelVersionId() == null || scope.deviceIds().isEmpty() || scope.deviceIds().size() > 20
                    || new HashSet<>(scope.deviceIds()).size() != scope.deviceIds().size()) {
                throw new IllegalArgumentException("分享变量候选非法");
            }
            devices.addAll(scope.deviceIds());
        }
        if (devices.size() > 20) throw new IllegalArgumentException("分享候选设备并集超过20台");
    }
}
