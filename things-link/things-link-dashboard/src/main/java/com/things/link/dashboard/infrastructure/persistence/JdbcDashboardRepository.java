package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardCreationResult;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardDraftSaveResult;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardPublicationAppendResult;
import com.things.link.dashboard.domain.DashboardPublicationRollbackResult;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardPublicationWithdrawalResult;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardSoftDeleteResult;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.DashboardVersionLookupResult;
import com.things.link.dashboard.domain.DashboardVersionSummary;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用Spring JDBC映射看板目录、独立草稿、创建恢复映射、不可变版本及精确模型关系。
 *
 * <p>所有管理查询显式携带projectId并由RLS再次裁剪。草稿保存先在目录行锁内完成结构化CAS分类，
 * 仅成功时整组替换关系；发布锁与受控追加/回滚只能加入外层非只读事务，使版本、关系、指针和审计共用失败边界。</p>
 */
@Repository
public class JdbcDashboardRepository implements DashboardRepository {

    /** 只解析数据库已经接受的JSONB文本，不在映射阶段重跑Schema业务校验。 */
    private static final ObjectReader JSON_READER = JsonMapper.builder().build().readerFor(JsonNode.class);
    /** 目录事实的唯一行映射。 */
    private static final RowMapper<DashboardCatalogEntry> DASHBOARD_MAPPER = (result, row) ->
            new DashboardCatalogEntry(result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    result.getString("management_name"), result.getLong("publication_revision"),
                    result.getObject("current_version_id", UUID.class),
                    result.getObject("created_by", UUID.class), result.getObject("updated_by", UUID.class),
                    result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                    toInstant(result.getTimestamp("deleted_at")));
    /** 创建恢复映射不包含原始请求，只映射身份与两个域分离摘要。 */
    private static final RowMapper<DashboardCreationResult> CREATION_RESULT_MAPPER = (result, row) ->
            new DashboardCreationResult(
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("project_id", UUID.class),
                    result.getObject("account_id", UUID.class),
                    result.getString("idempotency_key_digest"),
                    result.getString("request_digest"),
                    result.getObject("dashboard_id", UUID.class));
    /** 带同一语句聚合模型关系的草稿行映射。 */
    private static final RowMapper<DashboardDraft> DRAFT_MAPPER = (result, row) ->
            new DashboardDraft(result.getObject("dashboard_id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    readJson(result, "content"), result.getLong("revision"),
                    result.getObject("updated_by", UUID.class), result.getTimestamp("created_at").toInstant(),
                    result.getTimestamp("updated_at").toInstant(), readModelReferences(result));
    /** 带同一语句聚合模型关系的不可变版本行映射。 */
    private static final RowMapper<DashboardVersion> VERSION_MAPPER = (result, row) ->
            new DashboardVersion(result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    result.getObject("dashboard_id", UUID.class), result.getLong("version_number"),
                    result.getLong("source_draft_revision"), readJson(result, "schema"),
                    result.getString("schema_version"), result.getString("schema_digest_algorithm"),
                    result.getString("schema_digest"), readJson(result, "required_components"),
                    readJson(result, "required_resources"),
                    result.getObject("published_by_account_id", UUID.class),
                    result.getTimestamp("published_at").toInstant(), readModelReferences(result));
    /** 单语句历史分页的外连接行映射；空summary精确表示可见看板尚无版本。 */
    private static final RowMapper<VersionSummaryRow> VERSION_SUMMARY_ROW_MAPPER = (result, row) -> {
        UUID versionId = result.getObject("version_id", UUID.class);
        if (versionId == null) {
            return new VersionSummaryRow(null);
        }
        return new VersionSummaryRow(new DashboardVersionSummary(
                versionId,
                result.getObject("version_tenant_id", UUID.class),
                result.getObject("version_project_id", UUID.class),
                result.getObject("version_dashboard_id", UUID.class),
                result.getLong("version_number"),
                result.getLong("source_draft_revision"),
                result.getString("schema_version"),
                result.getString("schema_digest_algorithm"),
                result.getString("schema_digest"),
                result.getTimestamp("published_at").toInstant()));
    };
    /** 管理详情单语句先由外层可见目录造行，再按版本列是否为空形成第二级分类。 */
    private static final RowMapper<DashboardVersionLookupResult> MANAGEMENT_VERSION_MAPPER = (result, row) ->
            result.getObject("id", UUID.class) == null
                    ? DashboardVersionLookupResult.versionNotFound()
                    : DashboardVersionLookupResult.found(VERSION_MAPPER.mapRow(result, row));

    /** 已配置项目RLS上下文的数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建看板聚合JDBC适配器。
     *
     * @param jdbcTemplate 已配置项目RLS上下文的数据访问模板
     */
    public JdbcDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional
    public void create(DashboardCatalogEntry dashboard, DashboardDraft draft) {
        requireInitialAggregate(dashboard, draft);
        int createdDashboards = jdbcTemplate.update("""
                INSERT INTO dash_dashboard(
                    id, tenant_id, project_id, management_name, publication_revision,
                    current_version_id, created_by, updated_by, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL)
                """, dashboard.id(), dashboard.tenantId(), dashboard.projectId(), dashboard.managementName(),
                dashboard.createdBy(), dashboard.updatedBy(), Timestamp.from(dashboard.createdAt()),
                Timestamp.from(dashboard.updatedAt()));
        if (createdDashboards != 1) {
            throw new DataIntegrityViolationException("看板目录创建未产生唯一事实");
        }
        int createdDrafts = jdbcTemplate.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision,
                    updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 0, ?, ?, ?)
                """, draft.dashboardId(), draft.tenantId(), draft.projectId(), draft.content().toString(),
                draft.updatedBy(), Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()));
        if (createdDrafts != 1) {
            throw new DataIntegrityViolationException("看板初始草稿创建未产生唯一事实");
        }
        insertDraftModelReferences(draft.tenantId(), draft.projectId(), draft.dashboardId(),
                draft.modelReferences());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenantCapacity(UUID tenantId) {
        requireWritableTransaction();
        requireReadCommittedSnapshot("看板容量锁");
        Objects.requireNonNull(tenantId, "tenantId");
        jdbcTemplate.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?::text, 12042))",
                Integer.class, "tenant-dashboard-capacity-v1:" + tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long countTenantDashboards(UUID tenantId, UUID projectId) {
        Long count = jdbcTemplate.queryForObject("SELECT dashboard_tenant_capacity_count(?,?)",
                Long.class, tenantId, projectId);
        if (count == null) throw new IllegalStateException("看板容量计数归属或项目上下文不匹配");
        return count;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCreationRequest(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest) {
        requireWritableTransaction();
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(accountId, "accountId");
        requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        // 空缺映射没有行锁目标；事务级锁只负责串行，同一性仍由完整摘要和四轴主键判断。
        jdbcTemplate.queryForObject("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(
                    concat_ws(':', 'dashboard-create-v1', ?::text, ?::text, ?::text, ?), 12013::bigint))
                """, Integer.class, tenantId, projectId, accountId, idempotencyKeyDigest);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DashboardCreationResult> findCreationResult(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest) {
        requireWritableTransaction();
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(accountId, "accountId");
        requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        return jdbcTemplate.query("""
                SELECT tenant_id, project_id, account_id, idempotency_key_digest,
                       request_digest, dashboard_id
                  FROM dash_dashboard_creation_result
                 WHERE tenant_id = ? AND project_id = ? AND account_id = ?
                   AND idempotency_key_digest = ?
                """, CREATION_RESULT_MAPPER,
                tenantId, projectId, accountId, idempotencyKeyDigest).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DashboardCatalogEntry> findCreationDashboard(UUID projectId, UUID dashboardId) {
        requireWritableTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM dash_dashboard
                 WHERE project_id = ? AND id = ?
                 FOR UPDATE
                """, DASHBOARD_MAPPER, projectId, dashboardId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createIdempotent(
            DashboardCatalogEntry dashboard,
            DashboardDraft draft,
            DashboardCreationResult creationResult) {
        requireWritableTransaction();
        requireInitialAggregate(dashboard, draft);
        requireCreationResult(dashboard, creationResult);
        // 外层事务保证目录、草稿、模型关系、恢复映射及随后审计形成一个提交单元。
        create(dashboard, draft);
        int created = jdbcTemplate.update("""
                INSERT INTO dash_dashboard_creation_result(
                    tenant_id, project_id, account_id, idempotency_key_digest,
                    request_digest, dashboard_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, creationResult.tenantId(), creationResult.projectId(), creationResult.accountId(),
                creationResult.idempotencyKeyDigest(), creationResult.requestDigest(),
                creationResult.dashboardId(), Timestamp.from(dashboard.createdAt()));
        if (created != 1) {
            throw new DataIntegrityViolationException("看板创建恢复映射未产生唯一事实");
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardCatalogEntry> find(UUID projectId, UUID dashboardId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM dash_dashboard
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, DASHBOARD_MAPPER, projectId, dashboardId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public CursorPage<DashboardCatalogEntry> page(UUID projectId, String cursor, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        if (limit < 1 || limit > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板目录页大小必须在1至200之间");
        }
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, project_id, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM dash_dashboard
                 WHERE project_id = ? AND deleted_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        if (position != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id > ?))");
            arguments.add(Timestamp.from(position.updatedAt()));
            arguments.add(Timestamp.from(position.updatedAt()));
            arguments.add(position.dashboardId());
        }
        sql.append(" ORDER BY updated_at DESC, id ASC LIMIT ?");
        arguments.add(limit + 1);
        List<DashboardCatalogEntry> rows = jdbcTemplate.query(
                sql.toString(), DASHBOARD_MAPPER, arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<DashboardCatalogEntry> items = List.copyOf(rows.subList(0, limit));
        DashboardCatalogEntry last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.updatedAt() + "|" + last.id()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<CursorPage<DashboardVersionSummary>> pageVersions(
            UUID projectId, UUID dashboardId, String cursor, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        if (limit < 1 || limit > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板版本页大小必须在1至200之间");
        }
        Long position = decodeVersionCursor(cursor);
        String cursorClause = position == null ? "" : " AND candidate.version_number < ?";
        String sql = """
                WITH visible_dashboard AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM dash_dashboard
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                )
                SELECT dashboard.id AS visible_dashboard_id,
                       version.id AS version_id,
                       version.tenant_id AS version_tenant_id,
                       version.project_id AS version_project_id,
                       version.dashboard_id AS version_dashboard_id,
                       version.version_number,
                       version.source_draft_revision,
                       version.schema_version,
                       version.schema_digest_algorithm,
                       version.schema_digest,
                       version.published_at
                  FROM visible_dashboard dashboard
                  LEFT JOIN LATERAL (
                      SELECT candidate.id, candidate.tenant_id, candidate.project_id,
                             candidate.dashboard_id, candidate.version_number,
                             candidate.source_draft_revision, candidate.schema_version,
                             candidate.schema_digest_algorithm, candidate.schema_digest,
                             candidate.published_at
                        FROM dash_dashboard_version candidate
                       WHERE candidate.tenant_id = dashboard.tenant_id
                         AND candidate.project_id = dashboard.project_id
                         AND candidate.dashboard_id = dashboard.id
                """ + cursorClause + """
                       ORDER BY candidate.version_number DESC
                       LIMIT ?
                  ) version ON true
                 ORDER BY version.version_number DESC NULLS LAST
                """;
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        arguments.add(dashboardId);
        if (position != null) {
            arguments.add(position);
        }
        arguments.add(limit + 1);
        List<VersionSummaryRow> rows = jdbcTemplate.query(
                sql, VERSION_SUMMARY_ROW_MAPPER, arguments.toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<DashboardVersionSummary> summaries = rows.stream()
                .map(VersionSummaryRow::summary)
                .filter(Objects::nonNull)
                .toList();
        if (summaries.size() <= limit) {
            return Optional.of(CursorPage.last(summaries));
        }
        List<DashboardVersionSummary> items = List.copyOf(summaries.subList(0, limit));
        DashboardVersionSummary last = items.getLast();
        return Optional.of(CursorPage.of(
                items, Cursor.encode(Long.toString(last.versionNumber()))));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean rename(UUID projectId, UUID dashboardId, String managementName,
                          UUID updatedBy, Instant updatedAt) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(managementName, "managementName");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return jdbcTemplate.update("""
                UPDATE dash_dashboard
                   SET management_name = ?, updated_by = ?, updated_at = ?
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, managementName, updatedBy, Timestamp.from(updatedAt), projectId, dashboardId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardDraft> findDraft(UUID projectId, UUID dashboardId) {
        return jdbcTemplate.query("""
                SELECT draft.dashboard_id, draft.tenant_id, draft.project_id, draft.content,
                       draft.revision, draft.updated_by, draft.created_at, draft.updated_at,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'position', reference.position,
                                      'modelKey', reference.model_key,
                                      'thingModelVersionId', reference.thing_model_version_id)
                                      ORDER BY reference.position)
                             FROM dash_dashboard_draft_model_ref reference
                            WHERE reference.tenant_id = draft.tenant_id
                              AND reference.project_id = draft.project_id
                              AND reference.dashboard_id = draft.dashboard_id
                       ), '[]'::jsonb) AS model_references
                  FROM dash_dashboard_draft draft
                  JOIN dash_dashboard dashboard
                    ON dashboard.tenant_id = draft.tenant_id
                   AND dashboard.project_id = draft.project_id
                   AND dashboard.id = draft.dashboard_id
                 WHERE draft.project_id = ? AND draft.dashboard_id = ?
                   AND dashboard.deleted_at IS NULL
                """, DRAFT_MAPPER, projectId, dashboardId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional
    public DashboardDraftSaveResult saveDraft(
            UUID projectId, UUID dashboardId, long expectedRevision, JsonNode content,
            List<DashboardModelReference> modelReferences, UUID updatedBy, Instant updatedAt) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision不得为负数");
        }
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // JsonNode和调用方列表均可能可变；SQL前冻结并核对单一候选，保证内容与关系来自同次观察。
        JsonNode contentSnapshotNode = content.deepCopy();
        List<DashboardModelReference> referenceSnapshot =
                DashboardModelReference.requireExactSchemaProjection(contentSnapshotNode, modelReferences);
        String contentSnapshot = contentSnapshotNode.toString();
        DraftSaveAttempt attempt = jdbcTemplate.queryForObject("""
                WITH live_dashboard AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM dash_dashboard
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                     FOR UPDATE
                ), current_draft AS MATERIALIZED (
                    SELECT draft.dashboard_id, draft.tenant_id, draft.project_id, draft.content,
                           draft.revision, draft.updated_by, draft.created_at, draft.updated_at
                      FROM dash_dashboard_draft draft
                      JOIN live_dashboard dashboard
                        ON dashboard.tenant_id = draft.tenant_id
                       AND dashboard.project_id = draft.project_id
                       AND dashboard.id = draft.dashboard_id
                ), saved AS (
                UPDATE dash_dashboard_draft draft
                   SET content = ?::jsonb, revision = draft.revision + 1,
                       updated_by = ?, updated_at = ?
                  FROM current_draft current
                 WHERE draft.tenant_id = current.tenant_id
                   AND draft.project_id = current.project_id
                   AND draft.dashboard_id = current.dashboard_id
                   AND draft.revision = current.revision
                   AND current.project_id = ? AND current.dashboard_id = ?
                   AND current.revision = ? AND current.revision < 9223372036854775807
                RETURNING draft.dashboard_id, draft.tenant_id, draft.project_id, draft.content,
                          draft.revision, draft.updated_by, draft.created_at, draft.updated_at
                ), classified AS (
                    SELECT CASE
                               WHEN EXISTS (SELECT 1 FROM saved) THEN 'SAVED'
                               WHEN NOT EXISTS (SELECT 1 FROM live_dashboard) THEN 'NOT_FOUND'
                               WHEN NOT EXISTS (SELECT 1 FROM current_draft) THEN 'DATABASE_INCONSISTENT'
                               WHEN EXISTS (SELECT 1 FROM current_draft
                                             WHERE revision = 9223372036854775807)
                                   THEN 'REVISION_EXHAUSTED'
                               ELSE 'REVISION_CONFLICT'
                           END AS save_status
                )
                SELECT classified.save_status,
                       saved.dashboard_id, saved.tenant_id, saved.project_id, saved.content,
                       saved.revision, saved.updated_by, saved.created_at, saved.updated_at
                  FROM classified
                  LEFT JOIN saved ON true
                """, (result, row) -> mapDraftSaveAttempt(result), projectId, dashboardId,
                contentSnapshot, updatedBy, Timestamp.from(updatedAt), projectId, dashboardId,
                expectedRevision);
        if (attempt == null) {
            throw new DataIntegrityViolationException("看板草稿保存未返回分类结果");
        }
        if (attempt.status() != DraftSaveStatus.SAVED) {
            return switch (attempt.status()) {
                case NOT_FOUND -> DashboardDraftSaveResult.notFound();
                case REVISION_CONFLICT -> DashboardDraftSaveResult.revisionConflict();
                case REVISION_EXHAUSTED -> DashboardDraftSaveResult.revisionExhausted();
                case SAVED -> throw new IllegalStateException("成功状态必须携带草稿事实");
            };
        }
        DraftSnapshot saved = Objects.requireNonNull(attempt.savedDraft(), "savedDraft");
        int deletedReferences = jdbcTemplate.update("""
                DELETE FROM dash_dashboard_draft_model_ref
                 WHERE tenant_id = ? AND project_id = ? AND dashboard_id = ?
                """, saved.tenantId(), saved.projectId(), saved.dashboardId());
        // 删除数量由旧Schema决定且最多20；这里不把数量当成功条件，只要求后续插入完整候选集合。
        if (deletedReferences > 20) {
            throw new DataIntegrityViolationException("看板草稿存在超过合同上限的模型关系");
        }
        insertDraftModelReferences(saved.tenantId(), saved.projectId(), saved.dashboardId(), referenceSnapshot);
        return DashboardDraftSaveResult.saved(new DashboardDraft(
                saved.dashboardId(), saved.tenantId(), saved.projectId(), saved.content(), saved.revision(),
                saved.updatedBy(), saved.createdAt(), saved.updatedAt(), referenceSnapshot));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardVersion> findVersion(UUID projectId, UUID dashboardId, UUID versionId) {
        return queryVersion(" AND version.id = ?", projectId, dashboardId, versionId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public DashboardVersionLookupResult findVersionForManagement(
            UUID projectId, UUID dashboardId, UUID versionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(versionId, "versionId");
        List<DashboardVersionLookupResult> rows = jdbcTemplate.query("""
                WITH visible_dashboard AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM dash_dashboard
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                )
                SELECT version.id, version.tenant_id, version.project_id, version.dashboard_id,
                       version.version_number, version.source_draft_revision, version.schema,
                       version.schema_version, version.schema_digest_algorithm, version.schema_digest,
                       version.required_components, version.required_resources,
                       version.published_by_account_id, version.published_at,
                       version.model_references
                  FROM visible_dashboard dashboard
                  LEFT JOIN LATERAL (
                      SELECT candidate.id, candidate.tenant_id, candidate.project_id,
                             candidate.dashboard_id, candidate.version_number,
                             candidate.source_draft_revision, candidate.schema,
                             candidate.schema_version, candidate.schema_digest_algorithm,
                             candidate.schema_digest, candidate.required_components,
                             candidate.required_resources, candidate.published_by_account_id,
                             candidate.published_at,
                             COALESCE((
                                 SELECT jsonb_agg(jsonb_build_object(
                                            'position', reference.position,
                                            'modelKey', reference.model_key,
                                            'thingModelVersionId', reference.thing_model_version_id)
                                            ORDER BY reference.position)
                                   FROM dash_dashboard_version_model_ref reference
                                  WHERE reference.tenant_id = candidate.tenant_id
                                    AND reference.project_id = candidate.project_id
                                    AND reference.dashboard_id = candidate.dashboard_id
                                    AND reference.dashboard_version_id = candidate.id
                             ), '[]'::jsonb) AS model_references
                        FROM dash_dashboard_version candidate
                       WHERE candidate.tenant_id = dashboard.tenant_id
                         AND candidate.project_id = dashboard.project_id
                         AND candidate.dashboard_id = dashboard.id
                         AND candidate.id = ?
                  ) version ON true
                """, MANAGEMENT_VERSION_MAPPER, projectId, dashboardId, versionId);
        if (rows.isEmpty()) {
            return DashboardVersionLookupResult.dashboardNotFound();
        }
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("看板管理详情查询返回非唯一分类");
        }
        return rows.getFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardVersion> findLatestVersion(UUID projectId, UUID dashboardId) {
        return queryVersion(" ORDER BY version.version_number DESC LIMIT 1", projectId, dashboardId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardPublicationState> findPublicationState(UUID projectId, UUID dashboardId) {
        return queryPublicationState(projectId, dashboardId, "");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DashboardPublicationState> lockPublicationState(UUID projectId, UUID dashboardId) {
        requireWritableTransaction();
        requireReadCommittedSnapshot("看板发布状态锁");
        if (!acquirePublicationLock(projectId, dashboardId)) {
            return Optional.empty();
        }
        // READ COMMITTED下等待行锁后必须另起语句读取版本聚合，否则同一语句的旧快照可能把新指针与版本号0拼在一起。
        return queryPublicationState(projectId, dashboardId, "");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DashboardPublicationAppendResult appendPublication(
            DashboardVersion version, long expectedPublicationRevision) {
        requireWritableTransaction();
        Objects.requireNonNull(version, "version");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<DashboardPublicationAppendResult> rows = jdbcTemplate.query("""
                SELECT publication_status, version_number, publication_revision
                  FROM public.dashboard_publish_version(
                       ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, (result, row) -> new DashboardPublicationAppendResult(
                        DashboardPublicationAppendResult.Status.valueOf(
                                result.getString("publication_status")),
                        result.getObject("version_number", Long.class),
                        result.getObject("publication_revision", Long.class)),
                version.projectId(), version.dashboardId(), version.sourceDraftRevision(),
                expectedPublicationRevision, version.id(), version.schema().toString(),
                version.schemaVersion(), version.schemaDigestAlgorithm(), version.schemaDigest(),
                version.requiredComponents().toString(), version.requiredResources().toString(),
                version.publishedByAccountId(), Timestamp.from(version.publishedAt()));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("看板受控发布入口未返回唯一分类结果");
        }
        DashboardPublicationAppendResult result = rows.getFirst();
        if (result.status() == DashboardPublicationAppendResult.Status.PUBLISHED
                && result.publishedVersionNumber().orElseThrow() != version.versionNumber()) {
            throw new DataIntegrityViolationException("看板受控发布入口分配了非预期版本号");
        }
        if (result.status() == DashboardPublicationAppendResult.Status.PUBLISHED
                && result.observedPublicationRevision().orElseThrow() != expectedPublicationRevision + 1) {
            throw new DataIntegrityViolationException("看板受控发布入口推进了非预期发布revision");
        }
        return result;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DashboardPublicationRollbackResult rollbackPublication(
            UUID projectId, UUID dashboardId, UUID targetVersionId,
            long expectedPublicationRevision, UUID updatedBy, Instant updatedAt) {
        requireWritableTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(targetVersionId, "targetVersionId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<DashboardPublicationRollbackResult> rows = jdbcTemplate.query("""
                SELECT rollback_status, publication_revision
                  FROM public.dashboard_rollback_version(?, ?, ?, ?, ?, ?)
                """, (result, row) -> new DashboardPublicationRollbackResult(
                        DashboardPublicationRollbackResult.Status.valueOf(
                                result.getString("rollback_status")),
                        result.getObject("publication_revision", Long.class)),
                projectId, dashboardId, targetVersionId, expectedPublicationRevision,
                updatedBy, Timestamp.from(updatedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("看板受控回滚入口未返回唯一分类结果");
        }
        DashboardPublicationRollbackResult result = rows.getFirst();
        if (result.status() == DashboardPublicationRollbackResult.Status.ROLLED_BACK
                && result.observedPublicationRevision().orElseThrow()
                != expectedPublicationRevision + 1) {
            throw new DataIntegrityViolationException("看板受控回滚入口推进了非预期发布revision");
        }
        return result;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DashboardPublicationWithdrawalResult withdrawPublication(
            UUID projectId, UUID dashboardId, long expectedPublicationRevision,
            UUID updatedBy, Instant updatedAt) {
        requireWritableTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<DashboardPublicationWithdrawalResult> rows = jdbcTemplate.query("""
                SELECT withdrawal_status, previous_version_id, publication_revision
                  FROM public.dashboard_withdraw_publication(?, ?, ?, ?, ?)
                """, (result, row) -> new DashboardPublicationWithdrawalResult(
                        DashboardPublicationWithdrawalResult.Status.valueOf(
                                result.getString("withdrawal_status")),
                        result.getObject("previous_version_id", UUID.class),
                        result.getObject("publication_revision", Long.class)),
                projectId, dashboardId, expectedPublicationRevision,
                updatedBy, Timestamp.from(updatedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("看板受控撤回入口未返回唯一分类结果");
        }
        DashboardPublicationWithdrawalResult result = rows.getFirst();
        if (result.status() == DashboardPublicationWithdrawalResult.Status.WITHDRAWN
                && result.observedPublicationRevision().orElseThrow()
                != expectedPublicationRevision + 1) {
            throw new DataIntegrityViolationException("看板受控撤回入口推进了非预期发布revision");
        }
        return result;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DashboardSoftDeleteResult softDelete(
            UUID projectId, UUID dashboardId, long expectedPublicationRevision,
            UUID updatedBy, Instant deletedAt) {
        requireWritableTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(deletedAt, "deletedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<DashboardSoftDeleteResult> rows = jdbcTemplate.query("""
                SELECT deletion_status, previous_version_id, publication_revision, deleted_at
                  FROM public.dashboard_soft_delete(?, ?, ?, ?, ?)
                """, (result, row) -> new DashboardSoftDeleteResult(
                        DashboardSoftDeleteResult.Status.valueOf(result.getString("deletion_status")),
                        result.getObject("previous_version_id", UUID.class),
                        result.getObject("publication_revision", Long.class),
                        result.getTimestamp("deleted_at") == null
                                ? null : result.getTimestamp("deleted_at").toInstant()),
                projectId, dashboardId, expectedPublicationRevision,
                updatedBy, Timestamp.from(deletedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("看板受控软删入口未返回唯一分类结果");
        }
        DashboardSoftDeleteResult result = rows.getFirst();
        if (result.status() == DashboardSoftDeleteResult.Status.DELETED
                && result.observedPublicationRevision().orElseThrow()
                != expectedPublicationRevision + 1) {
            throw new DataIntegrityViolationException("看板受控软删入口推进了非预期发布revision");
        }
        return result;
    }

    /** 创建恢复、发布状态锁与高权限写入口必须共用服务层非只读事务，禁止仓储直调提前释放锁或漏写审计。 */
    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("看板受控持久操作必须加入已有非只读事务");
        }
    }

    /** 两语句锁后重读依赖每条语句取得新快照；RR/Serializable无法满足该并发前置，必须在加锁前拒绝。 */
    private void requireReadCommittedSnapshot(String operation) {
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException(operation + "要求READ COMMITTED或READ UNCOMMITTED事务");
        }
    }

    /** 先只取得目录与草稿锁，避免把锁等待前的语句快照用于后续历史版本聚合。 */
    private boolean acquirePublicationLock(UUID projectId, UUID dashboardId) {
        return !jdbcTemplate.query("""
                SELECT dashboard.id
                  FROM dash_dashboard dashboard
                  JOIN dash_dashboard_draft draft
                    ON draft.tenant_id = dashboard.tenant_id
                   AND draft.project_id = dashboard.project_id
                   AND draft.dashboard_id = dashboard.id
                 WHERE dashboard.project_id = ? AND dashboard.id = ?
                 FOR UPDATE OF dashboard, draft
                """, (result, row) -> result.getObject("id", UUID.class), projectId, dashboardId).isEmpty();
    }

    /** 读取或锁定看板的草稿、发布和版本号三条轴；固定后缀不接收外部输入。 */
    private Optional<DashboardPublicationState> queryPublicationState(
            UUID projectId, UUID dashboardId, String suffix) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        return jdbcTemplate.query("""
                SELECT dashboard.id, dashboard.tenant_id, dashboard.project_id,
                       draft.revision AS draft_revision, dashboard.publication_revision,
                       dashboard.current_version_id, dashboard.deleted_at,
                       COALESCE((SELECT MAX(version.version_number)
                                   FROM dash_dashboard_version version
                                  WHERE version.tenant_id = dashboard.tenant_id
                                    AND version.project_id = dashboard.project_id
                                    AND version.dashboard_id = dashboard.id), 0) AS latest_version_number
                  FROM dash_dashboard dashboard
                  JOIN dash_dashboard_draft draft
                    ON draft.tenant_id = dashboard.tenant_id
                   AND draft.project_id = dashboard.project_id
                   AND draft.dashboard_id = dashboard.id
                 WHERE dashboard.project_id = ? AND dashboard.id = ?
                """ + suffix, (result, row) -> new DashboardPublicationState(
                        result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                        result.getObject("project_id", UUID.class), result.getLong("draft_revision"),
                        result.getLong("publication_revision"),
                        result.getObject("current_version_id", UUID.class),
                        result.getLong("latest_version_number"), toInstant(result.getTimestamp("deleted_at"))),
                projectId, dashboardId).stream().findFirst();
    }

    /** 查询一个精确版本或看板内最大版本；固定SQL片段不接收外部输入。 */
    private Optional<DashboardVersion> queryVersion(String suffix, Object... arguments) {
        return jdbcTemplate.query("""
                SELECT version.id, version.tenant_id, version.project_id, version.dashboard_id,
                       version.version_number, version.source_draft_revision, version.schema,
                       version.schema_version, version.schema_digest_algorithm, version.schema_digest,
                       version.required_components, version.required_resources,
                       version.published_by_account_id, version.published_at,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'position', reference.position,
                                      'modelKey', reference.model_key,
                                      'thingModelVersionId', reference.thing_model_version_id)
                                      ORDER BY reference.position)
                             FROM dash_dashboard_version_model_ref reference
                            WHERE reference.tenant_id = version.tenant_id
                              AND reference.project_id = version.project_id
                              AND reference.dashboard_id = version.dashboard_id
                              AND reference.dashboard_version_id = version.id
                       ), '[]'::jsonb) AS model_references
                  FROM dash_dashboard_version version
                  JOIN dash_dashboard dashboard
                    ON dashboard.tenant_id = version.tenant_id
                   AND dashboard.project_id = version.project_id
                   AND dashboard.id = version.dashboard_id
                 WHERE version.project_id = ? AND version.dashboard_id = ?
                   AND dashboard.deleted_at IS NULL
                """ + suffix, VERSION_MAPPER, arguments).stream().findFirst();
    }

    /** 批量插入一份草稿的完整有序模型关系；空集合无需产生SQL。 */
    private void insertDraftModelReferences(
            UUID tenantId, UUID projectId, UUID dashboardId,
            List<DashboardModelReference> modelReferences) {
        if (modelReferences.isEmpty()) {
            return;
        }
        List<Object[]> batches = modelReferences.stream()
                .map(reference -> new Object[]{tenantId, projectId, dashboardId, reference.position(),
                        reference.modelKey(), reference.thingModelVersionId()})
                .toList();
        int[] inserted = jdbcTemplate.batchUpdate("""
                INSERT INTO dash_dashboard_draft_model_ref(
                    tenant_id, project_id, dashboard_id, position, model_key, thing_model_version_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, batches);
        for (int insertedRows : inserted) {
            if (insertedRows != 1) {
                throw new DataIntegrityViolationException("看板草稿模型关系插入未产生唯一事实");
            }
        }
    }

    /** 将目录行锁内的保存分类和可选UPDATE RETURNING事实映射为内部封闭结果。 */
    private static DraftSaveAttempt mapDraftSaveAttempt(ResultSet result) throws SQLException {
        return switch (result.getString("save_status")) {
            case "SAVED" -> new DraftSaveAttempt(DraftSaveStatus.SAVED,
                    new DraftSnapshot(result.getObject("dashboard_id", UUID.class),
                            result.getObject("tenant_id", UUID.class),
                            result.getObject("project_id", UUID.class), readJson(result, "content"),
                            result.getLong("revision"), result.getObject("updated_by", UUID.class),
                            result.getTimestamp("created_at").toInstant(),
                            result.getTimestamp("updated_at").toInstant()));
            case "NOT_FOUND" -> new DraftSaveAttempt(DraftSaveStatus.NOT_FOUND, null);
            case "REVISION_CONFLICT" -> new DraftSaveAttempt(DraftSaveStatus.REVISION_CONFLICT, null);
            case "REVISION_EXHAUSTED" -> new DraftSaveAttempt(DraftSaveStatus.REVISION_EXHAUSTED, null);
            case "DATABASE_INCONSISTENT" -> throw new DataIntegrityViolationException(
                    "活跃看板目录缺少唯一草稿事实");
            default -> throw new DataIntegrityViolationException("数据库返回未知看板草稿保存状态");
        };
    }

    /** 将不可信游标严格解码为目录复合排序位置。 */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("看板目录游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板目录分页游标无效");
        }
    }

    /** 将不可信版本游标严格解码为唯一正版本号，拒绝多种文本表示和Long溢出。 */
    private static Long decodeVersionCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        String payload = Cursor.decode(cursor);
        if (!payload.matches("[1-9][0-9]*")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板版本分页游标无效");
        }
        try {
            return Long.parseLong(payload);
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板版本分页游标无效");
        }
    }

    /** 创建必须同时满足初始目录、草稿和三轴归属。 */
    private static void requireInitialAggregate(DashboardCatalogEntry dashboard, DashboardDraft draft) {
        Objects.requireNonNull(dashboard, "dashboard");
        Objects.requireNonNull(draft, "draft");
        if (dashboard.publicationRevision() != 0 || dashboard.currentVersionId() != null
                || dashboard.deletedAt() != null || draft.revision() != 0
                || !dashboard.id().equals(draft.dashboardId())
                || !dashboard.tenantId().equals(draft.tenantId())
                || !dashboard.projectId().equals(draft.projectId())) {
            throw new IllegalArgumentException("创建看板必须提供同归属的未发布目录与revision 0草稿");
        }
    }

    /** 恢复映射必须精确指向同一次初始看板创建及其创建账号。 */
    private static void requireCreationResult(
            DashboardCatalogEntry dashboard, DashboardCreationResult creationResult) {
        Objects.requireNonNull(creationResult, "creationResult");
        if (!dashboard.tenantId().equals(creationResult.tenantId())
                || !dashboard.projectId().equals(creationResult.projectId())
                || !dashboard.id().equals(creationResult.dashboardId())
                || !dashboard.createdBy().equals(creationResult.accountId())) {
            throw new IllegalArgumentException("看板创建恢复映射必须与初始目录身份一致");
        }
    }

    /** 摘要形状在进入PostgreSQL前再次收紧，避免char填充掩盖调用错误。 */
    private static String requireDigest(String digest, String name) {
        Objects.requireNonNull(digest, name);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + "必须是SHA-256小写十六进制");
        }
        return digest;
    }

    /** 从数据库聚合JSON中恢复保持position顺序的精确模型关系。 */
    private static List<DashboardModelReference> readModelReferences(ResultSet result) throws SQLException {
        JsonNode references = readJson(result, "model_references");
        if (!references.isArray()) {
            throw new SQLException("看板模型关系聚合结果必须是数组");
        }
        List<DashboardModelReference> mapped = new ArrayList<>(references.size());
        try {
            for (JsonNode reference : references) {
                mapped.add(new DashboardModelReference(reference.path("position").asInt(),
                        reference.path("modelKey").asString(),
                        UUID.fromString(reference.path("thingModelVersionId").asString())));
            }
            return List.copyOf(mapped);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("看板模型关系事实无法映射", exception);
        }
    }

    /** 将数据库JSONB文本解析成隔离树；不可解析事实保留为SQL映射失败。 */
    private static JsonNode readJson(ResultSet result, String column) throws SQLException {
        try {
            return JSON_READER.readTree(result.getString(column));
        } catch (JacksonException exception) {
            throw new SQLException("看板JSONB事实无法解析", exception);
        }
    }

    /** PostgreSQL可空timestamptz到Instant的唯一转换。 */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 保存SQL能够返回的内部分类；不直接暴露数据库字符串。 */
    private enum DraftSaveStatus {
        /** 已更新草稿，随后必须替换关系。 */
        SAVED,
        /** 活目录不存在。 */
        NOT_FOUND,
        /** revision不匹配。 */
        REVISION_CONFLICT,
        /** revision达到Long上限。 */
        REVISION_EXHAUSTED
    }

    /** @param status 行锁内分类 @param savedDraft 成功UPDATE RETURNING事实 */
    private record DraftSaveAttempt(DraftSaveStatus status, DraftSnapshot savedDraft) {
    }

    /**
     * @param dashboardId 草稿看板ID
     * @param tenantId 草稿租户ID
     * @param projectId 草稿项目ID
     * @param content PostgreSQL返回的规范内容
     * @param revision 保存后的revision
     * @param updatedBy 保存账号
     * @param createdAt 原创建时刻
     * @param updatedAt 保存时刻
     */
    private record DraftSnapshot(
            UUID dashboardId, UUID tenantId, UUID projectId, JsonNode content,
            long revision, UUID updatedBy, Instant createdAt, Instant updatedAt) {
    }

    /** @param updatedAt 上一页末项更新时间 @param dashboardId 上一页末项稳定看板ID */
    private record CursorPosition(Instant updatedAt, UUID dashboardId) {
    }

    /** @param summary 轻量版本元数据；可见看板没有任何版本时为空 */
    private record VersionSummaryRow(DashboardVersionSummary summary) {
    }
}
