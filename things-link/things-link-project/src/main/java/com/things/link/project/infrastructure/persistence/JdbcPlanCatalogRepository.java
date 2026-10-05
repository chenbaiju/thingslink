package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.plan.PlanCatalogEntry;
import com.things.link.project.domain.plan.PlanCatalogRepository;
import com.things.link.project.domain.plan.PlanDefinition;
import com.things.link.project.domain.plan.PlanDimension;
import com.things.link.project.domain.plan.PlanEntitlement;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读写平台套餐目录。
 *
 * <p>目录是平台级事实、没有租户列，因此不能按租户 RLS 过滤；读取端只返回公开报价字段，
 * 不含订单、租户或密钥。写入端只提供「补齐缺失行」，没有任何 UPDATE 语句 ——
 * 已发布的修订版必须新建 {@code revision_no}。
 */
@Repository
public class JdbcPlanCatalogRepository implements PlanCatalogRepository {

    /** 修订版与稳定身份联合投影的列映射；子表在 {@link #assemble(List)} 中一次批量补全。 */
    private static final RowMapper<RevisionRow> REVISION_ROW_MAPPER = (resultSet, rowNumber) -> new RevisionRow(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("code"),
            resultSet.getInt("display_order"),
            resultSet.getString("revision_code"),
            resultSet.getInt("revision_no"),
            resultSet.getString("name"),
            resultSet.getString("sale_status"),
            resultSet.getString("billing_period"),
            resultSet.getString("currency"),
            resultSet.getObject("price_cents", Long.class),
            resultSet.getObject("reference_price_cents", Long.class),
            resultSet.getString("reference_price_currency"),
            resultSet.getTimestamp("valid_from").toInstant(),
            resultSet.getTimestamp("valid_until") == null ? null : resultSet.getTimestamp("valid_until").toInstant());

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcPlanCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<PlanCatalogEntry> findByProductRevision(String productRevision) {
        return assemble(jdbcTemplate.query("""
                SELECT p.code, p.display_order, r.id, r.revision_code, r.revision_no, r.name,
                       r.sale_status, r.billing_period, r.currency, r.price_cents,
                       r.reference_price_cents, r.reference_price_currency,
                       r.valid_from, r.valid_until
                  FROM sys_plan p
                  JOIN sys_plan_revision r ON r.plan_id = p.id
                 WHERE r.revision_code = ?
                 ORDER BY p.display_order
                """, REVISION_ROW_MAPPER, productRevision));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<PlanCatalogEntry> findEffectiveCatalog(Instant at) {
        Timestamp atTimestamp = Timestamp.from(at);
        return assemble(jdbcTemplate.query("""
                SELECT effective.code, effective.display_order, effective.id, effective.revision_code,
                       effective.revision_no, effective.name, effective.sale_status,
                       effective.billing_period, effective.currency, effective.price_cents,
                       effective.reference_price_cents, effective.reference_price_currency,
                       effective.valid_from, effective.valid_until
                  FROM (
                        SELECT DISTINCT ON (p.id)
                               p.code, p.display_order, r.id, r.revision_code, r.revision_no, r.name,
                               r.sale_status, r.billing_period, r.currency, r.price_cents,
                               r.reference_price_cents, r.reference_price_currency,
                               r.valid_from, r.valid_until
                          FROM sys_plan p
                          JOIN sys_plan_revision r ON r.plan_id = p.id
                         WHERE r.valid_from <= ?
                           AND (r.valid_until IS NULL OR r.valid_until > ?)
                         ORDER BY p.id, r.revision_no DESC
                       ) effective
                 ORDER BY effective.display_order
                """, REVISION_ROW_MAPPER, atTimestamp, atTimestamp));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<PlanCatalogEntry> findEffectiveByPlanCode(String planCode, Instant at) {
        Timestamp atTimestamp = Timestamp.from(at);
        return assemble(jdbcTemplate.query("""
                SELECT effective.code, effective.display_order, effective.id, effective.revision_code,
                       effective.revision_no, effective.name, effective.sale_status,
                       effective.billing_period, effective.currency, effective.price_cents,
                       effective.reference_price_cents, effective.reference_price_currency,
                       effective.valid_from, effective.valid_until
                  FROM (
                        SELECT DISTINCT ON (p.id)
                               p.code, p.display_order, r.id, r.revision_code, r.revision_no, r.name,
                               r.sale_status, r.billing_period, r.currency, r.price_cents,
                               r.reference_price_cents, r.reference_price_currency,
                               r.valid_from, r.valid_until
                          FROM sys_plan p
                          JOIN sys_plan_revision r ON r.plan_id = p.id
                         WHERE r.valid_from <= ?
                           AND (r.valid_until IS NULL OR r.valid_until > ?)
                           AND p.code = ?
                         ORDER BY p.id, r.revision_no DESC
                       ) effective
                 ORDER BY effective.display_order
                """, REVISION_ROW_MAPPER, atTimestamp, atTimestamp, planCode))
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void lockSeed(String productRevision) {
        // 事务级 advisory lock：同一修订版的并发播种串行化。hashtext 与业务键一一对应即可，
        // 锁只在本次事务内持有，不阻塞目录读取。
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                resultSet -> null, productRevision);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean insertIfAbsent(PlanDefinition definition, Instant validFrom) {
        jdbcTemplate.update("""
                INSERT INTO sys_plan (id, code, display_order)
                VALUES (?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, Uuid7.generate(), definition.code(), definition.displayOrder());
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_plan WHERE code = ?", UUID.class, definition.code());
        UUID existingRevisionId = jdbcTemplate.query("""
                        SELECT id FROM sys_plan_revision WHERE plan_id = ? AND revision_code = ?
                        """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                planId, definition.revision())
                .stream().findFirst().orElse(null);
        if (existingRevisionId != null) {
            // 修订版已存在：不补插、不改写任何权益或维度，漂移交给播种后的校验暴露。
            return false;
        }
        UUID revisionId = Uuid7.generate();
        // 参考价与参考价币种同生共死：未记录参考价时两列都为 NULL（不得用 0 冒充「没定参考价」）。
        String referencePriceCurrency =
                definition.referencePriceCents() == null ? null : definition.currency();
        int inserted = jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents,
                                               reference_price_cents, reference_price_currency,
                                               valid_from, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::bigint, ?::bigint, ?, ?, NULL)
                ON CONFLICT (plan_id, revision_code) DO NOTHING
                """, revisionId, planId, definition.revision(), definition.revisionNo(), definition.name(),
                definition.saleStatus(), definition.billingPeriod(), definition.currency(),
                definition.priceCents(), definition.referencePriceCents(), referencePriceCurrency,
                Timestamp.from(validFrom));
        if (inserted == 0) {
            // 并发播种已建同修订版：由对方补齐子表，本事务不再插入半份快照。
            return false;
        }
        for (PlanDimension dimension : definition.dimensions()) {
            jdbcTemplate.update("""
                    INSERT INTO sys_plan_revision_dimension
                        (plan_revision_id, dimension_code, value_amount, unit, window_kind)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (plan_revision_id, dimension_code) DO NOTHING
                    """, revisionId, dimension.code(), dimension.value(), dimension.unit(), dimension.window());
        }
        for (PlanEntitlement entitlement : definition.entitlements()) {
            jdbcTemplate.update("""
                    INSERT INTO sys_plan_entitlement (plan_revision_id, capability_code, state)
                    VALUES (?, ?, ?)
                    ON CONFLICT (plan_revision_id, capability_code) DO NOTHING
                    """, revisionId, entitlement.code(), entitlement.enabled() ? "ENABLED" : "DISABLED");
        }
        return true;
    }

    /**
     * 把修订版行与子表一次批量组装成快照。
     *
     * <p>维度与权益各用一条 {@code ANY(?)} 查询按修订版 ID 批量取回，避免每个档位两次往返。
     *
     * @param rows 修订版联合投影行，已按展示顺序
     * @return 完整快照列表
     */
    private List<PlanCatalogEntry> assemble(List<RevisionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> revisionIds = rows.stream().map(RevisionRow::revisionId).toList();
        Map<UUID, List<PlanDimension>> dimensions = loadDimensions(revisionIds);
        Map<UUID, List<PlanEntitlement>> entitlements = loadEntitlements(revisionIds);
        return rows.stream()
                .map(row -> new PlanCatalogEntry(row.code(), row.displayOrder(), row.revisionCode(),
                        row.revisionNo(), row.name(), row.saleStatus(), row.billingPeriod(), row.currency(),
                        row.priceCents(), row.referencePriceCents(), row.referencePriceCurrency(),
                        row.validFrom(), row.validUntil(),
                        dimensions.getOrDefault(row.revisionId(), List.of()),
                        entitlements.getOrDefault(row.revisionId(), List.of())))
                .toList();
    }

    /**
     * 按修订版 ID 批量读取冻结维度。
     *
     * @param revisionIds 修订版 ID 列表，非空
     * @return 修订版 ID 到维度列表的映射，维度按编码排序
     */
    private Map<UUID, List<PlanDimension>> loadDimensions(List<UUID> revisionIds) {
        return jdbcTemplate.query("""
                        SELECT plan_revision_id, dimension_code, value_amount, unit, window_kind
                          FROM sys_plan_revision_dimension
                         WHERE plan_revision_id = ANY(?)
                         ORDER BY dimension_code
                        """,
                statement -> statement.setArray(1,
                        statement.getConnection().createArrayOf("uuid", revisionIds.toArray(UUID[]::new))),
                resultSet -> {
                    Map<UUID, List<PlanDimension>> grouped = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        grouped.computeIfAbsent(resultSet.getObject("plan_revision_id", UUID.class),
                                        key -> new ArrayList<>())
                                .add(new PlanDimension(resultSet.getString("dimension_code"),
                                        resultSet.getLong("value_amount"),
                                        resultSet.getString("unit"), resultSet.getString("window_kind")));
                    }
                    return grouped;
                });
    }

    /**
     * 按修订版 ID 批量读取功能权益。
     *
     * @param revisionIds 修订版 ID 列表，非空
     * @return 修订版 ID 到权益列表的映射，权益按编码排序
     */
    private Map<UUID, List<PlanEntitlement>> loadEntitlements(List<UUID> revisionIds) {
        return jdbcTemplate.query("""
                        SELECT plan_revision_id, capability_code, state
                          FROM sys_plan_entitlement
                         WHERE plan_revision_id = ANY(?)
                         ORDER BY capability_code
                        """,
                statement -> statement.setArray(1,
                        statement.getConnection().createArrayOf("uuid", revisionIds.toArray(UUID[]::new))),
                resultSet -> {
                    Map<UUID, List<PlanEntitlement>> grouped = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        grouped.computeIfAbsent(resultSet.getObject("plan_revision_id", UUID.class),
                                        key -> new ArrayList<>())
                                .add(new PlanEntitlement(resultSet.getString("capability_code"),
                                        "ENABLED".equals(resultSet.getString("state"))));
                    }
                    return grouped;
                });
    }

    /**
     * 修订版联合投影行。
     *
     * @param revisionId 修订版 ID
     * @param code 稳定套餐编码
     * @param displayOrder 展示顺序
     * @param revisionCode 产品修订版标识
     * @param revisionNo 修订序号
     * @param name 展示名称
     * @param saleStatus 销售状态
     * @param billingPeriod 计费周期
     * @param currency 币种
     * @param priceCents 成交价（人民币分）；未开售时为 {@code null}
     * @param referencePriceCents 参考价（人民币分）；未记录时为 {@code null}
     * @param referencePriceCurrency 参考价币种；与参考价同生共死
     * @param validFrom 生效时刻
     * @param validUntil 失效时刻；{@code null} 表示未设
     */
    private record RevisionRow(UUID revisionId, String code, int displayOrder, String revisionCode,
                               int revisionNo, String name, String saleStatus, String billingPeriod,
                               String currency, Long priceCents, Long referencePriceCents,
                               String referencePriceCurrency, Instant validFrom, Instant validUntil) {
    }
}
