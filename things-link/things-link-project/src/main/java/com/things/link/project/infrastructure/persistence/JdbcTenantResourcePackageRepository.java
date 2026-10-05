package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.ResourcePackageAdjustment;
import com.things.link.project.domain.ResourcePackageSource;
import com.things.link.project.domain.ResourcePackageStatus;
import com.things.link.project.domain.TenantResourcePackage;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.plan.ResourcePackageAddition;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写租户资源包事实（S14-4a；S14-4b 增加人工调整写入）。
 *
 * <p>写入端没有 DELETE：到期与取消都是状态推进，包行始终保留。幂等由三层叠加：
 * ① 生效事务持订单行锁，同一订单的并发支付被串行化（人工调整改持租户行锁 + 幂等键唯一索引）；
 * ② {@code UPDATE ... WHERE status = ...} 让重复推进更新不到行；
 * ③ {@code sys_tenant_resource_package_source_order_uk} / {@code ..._adjustment_key_uk}
 * 分别拒绝同一来源订单、同一幂等键的第二行。
 *
 * <p>读取端额外提供「有效加数」投影：只累计 {@code status = 'ACTIVE'} 且处于自身
 * {@code [starts_at, ends_at)} 窗口内的包，按维度/单位/窗口分组求和 —— 购买与人工调整
 * 共用同一条求和规则，来源不参与过滤。合成规则不在这里展开（是否与基础档匹配由
 * {@code domain.plan} 判定），仓库只如实返回分组的加数。
 *
 * <p>包表与 {@code sys_tenant_order} 一样不套租户 RLS（下单时尚无 HTTP 租户上下文，
 * 授权留在应用入口），因此这里按显式参数过滤，不读 {@code app_current_tenant()}。
 */
@Repository
public class JdbcTenantResourcePackageRepository implements TenantResourcePackageRepository {

    /** 包行投影；人工调整元数据三列只在同一来源下同时非空（DB CHECK 保证）。 */
    private static final RowMapper<TenantResourcePackage> PACKAGE_ROW_MAPPER = (resultSet, rowNumber) ->
            new TenantResourcePackage(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getString("dimension_code"),
                    resultSet.getLong("amount"),
                    resultSet.getString("unit"),
                    resultSet.getString("window_kind"),
                    resultSet.getTimestamp("starts_at").toInstant(),
                    resultSet.getTimestamp("ends_at").toInstant(),
                    ResourcePackageSource.valueOf(resultSet.getString("source")),
                    resultSet.getObject("source_order_id", UUID.class),
                    mapAdjustment(resultSet),
                    ResourcePackageStatus.valueOf(resultSet.getString("status")),
                    resultSet.getLong("revision"));

    /**
     * 读取人工调整元数据；非调整行返回 {@code null}（包行的紧凑构造器要求来源与元数据同生共死）。
     *
     * @param resultSet 当前结果集行
     * @return 调整元数据；来源为购买时为 {@code null}
     * @throws SQLException 读取列失败时由 JDBC 抛出
     */
    private static ResourcePackageAdjustment mapAdjustment(ResultSet resultSet)
            throws SQLException {
        String reason = resultSet.getString("adjustment_reason");
        if (reason == null) {
            return null;
        }
        return new ResourcePackageAdjustment(reason,
                resultSet.getObject("adjustment_operator_id", UUID.class),
                resultSet.getString("adjustment_key"));
    }

    /** 包行全列；四处 SELECT 共用，避免漏列导致重建快照失败。 */
    private static final String PACKAGE_COLUMNS = """
            id, tenant_id, dimension_code, amount, unit, window_kind, starts_at, ends_at,
            source, source_order_id, adjustment_reason, adjustment_operator_id, adjustment_key,
            status, revision
            """;

    /** 资源包事实的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcTenantResourcePackageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public UUID insert(UUID tenantId, String dimensionCode, long amount, String unit, String window,
                       Instant startsAt, Instant endsAt, ResourcePackageSource source,
                       UUID sourceOrderId, ResourcePackageAdjustment adjustment,
                       ResourcePackageStatus status) {
        UUID packageId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_resource_package (
                    id, tenant_id, dimension_code, amount, unit, window_kind, starts_at, ends_at,
                    source, source_order_id, adjustment_reason, adjustment_operator_id, adjustment_key,
                    status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now(), now())
                """, packageId, tenantId, dimensionCode, amount, unit, window,
                Timestamp.from(startsAt), Timestamp.from(endsAt), source.name(), sourceOrderId,
                adjustment == null ? null : adjustment.reason(),
                adjustment == null ? null : adjustment.operatorId(),
                adjustment == null ? null : adjustment.idempotencyKey(),
                status.name());
        return packageId;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantResourcePackage> findById(UUID packageId) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package "
                        + "WHERE id = ?", PACKAGE_ROW_MAPPER, packageId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantResourcePackage> findBySourceOrder(UUID orderId) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package "
                        + "WHERE source_order_id = ?", PACKAGE_ROW_MAPPER, orderId)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantResourcePackage> findAdjustmentByKey(UUID tenantId, String idempotencyKey) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package "
                        + "WHERE tenant_id = ? AND adjustment_key = ? AND source = 'OPERATION_ADJUSTMENT'",
                PACKAGE_ROW_MAPPER, tenantId, idempotencyKey)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<TenantResourcePackage> findLiveByTenant(UUID tenantId) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package "
                        + "WHERE tenant_id = ? AND status IN ('ACTIVE', 'PENDING') "
                        + "ORDER BY starts_at, id",
                PACKAGE_ROW_MAPPER, tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<ResourcePackageAddition> findActiveAdditions(UUID tenantId, Instant at) {
        return jdbcTemplate.query("""
                        SELECT dimension_code, unit, window_kind, SUM(amount) AS total
                          FROM sys_tenant_resource_package
                         WHERE tenant_id = ?
                           AND status = 'ACTIVE'
                           AND starts_at <= ?
                           AND ends_at > ?
                         GROUP BY dimension_code, unit, window_kind
                         ORDER BY dimension_code, unit, window_kind
                        """, (resultSet, rowNumber) -> new ResourcePackageAddition(
                        resultSet.getString("dimension_code"),
                        resultSet.getString("unit"),
                        resultSet.getString("window_kind"),
                        resultSet.getLong("total")), tenantId, Timestamp.from(at), Timestamp.from(at));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<TenantResourcePackage> findDueForExpiry(Instant now, int limit) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package "
                        + "WHERE status = 'ACTIVE' AND ends_at <= ? ORDER BY ends_at LIMIT ?",
                PACKAGE_ROW_MAPPER, Timestamp.from(now), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<TenantResourcePackage> findDueForActivation(Instant now, int limit) {
        return jdbcTemplate.query("SELECT " + PACKAGE_COLUMNS + " FROM sys_tenant_resource_package p "
                        + "WHERE p.status = 'PENDING' AND p.starts_at <= ? "
                        + "AND EXISTS (SELECT 1 FROM sys_tenant_subscription s "
                        + "WHERE s.tenant_id = p.tenant_id AND s.status IN ('ACTIVE', 'GRACE')) "
                        + "ORDER BY p.starts_at LIMIT ?",
                PACKAGE_ROW_MAPPER, Timestamp.from(now), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markExpired(UUID packageId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_resource_package
                   SET status = 'EXPIRED', updated_at = now(), revision = revision + 1
                 WHERE id = ? AND status = 'ACTIVE' AND ends_at <= ?
                """, packageId, Timestamp.from(now)) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean activatePending(UUID packageId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_resource_package
                   SET status = 'ACTIVE', updated_at = now(), revision = revision + 1
                 WHERE id = ? AND status = 'PENDING' AND starts_at <= ?
                """, packageId, Timestamp.from(now)) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean cancelAdjustment(UUID packageId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_resource_package
                   SET status = 'CANCELLED', updated_at = ?, revision = revision + 1
                 WHERE id = ?
                   AND source = 'OPERATION_ADJUSTMENT'
                   AND status IN ('ACTIVE', 'PENDING')
                """, Timestamp.from(now), packageId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markRefunded(UUID packageId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE sys_tenant_resource_package
                   SET status = 'REFUNDED', updated_at = ?, revision = revision + 1
                 WHERE id = ?
                   AND source = 'PURCHASE'
                   AND status IN ('ACTIVE', 'PENDING')
                """, Timestamp.from(now), packageId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean isCurrentlyEffective(UUID packageId) {
        // 判定与合成函数 tenant_resource_package_addon() 用同一个时间基准（数据库 now()），
        // 不把数据库写入的窗口拿到 JVM 时钟上比较；行不存在时 fail-closed 返回 false。
        return jdbcTemplate.query("""
                        SELECT status = 'ACTIVE' AND starts_at <= now()
                               AND (ends_at IS NULL OR ends_at > now()) AS effective
                          FROM sys_tenant_resource_package
                         WHERE id = ?
                        """, (resultSet, rowNumber) -> resultSet.getBoolean("effective"), packageId)
                .stream().findFirst().orElse(false);
    }
}
