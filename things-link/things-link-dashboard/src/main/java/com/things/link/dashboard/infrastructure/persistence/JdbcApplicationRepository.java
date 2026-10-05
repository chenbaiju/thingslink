package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.ApplicationCatalogEntry;
import com.things.link.dashboard.domain.ApplicationCreationResult;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationDraftSaveResult;
import com.things.link.dashboard.domain.ApplicationKeyCollisionException;
import com.things.link.dashboard.domain.ApplicationPublicationAppendResult;
import com.things.link.dashboard.domain.ApplicationPublicationRollbackResult;
import com.things.link.dashboard.domain.ApplicationPublicationState;
import com.things.link.dashboard.domain.ApplicationPublicationWithdrawalResult;
import com.things.link.dashboard.domain.ApplicationRepository;
import com.things.link.dashboard.domain.ApplicationSoftDeleteResult;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.domain.ApplicationVersionLookupResult;
import com.things.link.dashboard.domain.ApplicationVersionSummary;
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
import tools.jackson.databind.node.ArrayNode;

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
 * 使用Spring JDBC映射WebApp应用聚合的目录、草稿、创建恢复映射和不可变版本事实。
 *
 * <p>所有管理查询显式携带projectId，并由数据库RLS再次裁剪。仓储不读取线程上下文，也不提供
 * 无可信项目范围的appKey公开解析；该入口需后续单独设计受限定位能力。</p>
 */
@Repository
public class JdbcApplicationRepository implements ApplicationRepository {

    /** 构造受控函数精确看板关系参数的内部JSON映射器。 */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    /** 只解析数据库已经接受的JSONB文本，不在映射时重解释业务字段。 */
    private static final ObjectReader JSON_READER = JSON_MAPPER.readerFor(JsonNode.class);
    /** 目录事实的唯一行映射。 */
    private static final RowMapper<ApplicationCatalogEntry> APPLICATION_MAPPER = (result, row) ->
            new ApplicationCatalogEntry(result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    result.getString("app_key"), result.getString("management_name"),
                    result.getLong("publication_revision"), result.getObject("current_version_id", UUID.class),
                    result.getObject("created_by", UUID.class), result.getObject("updated_by", UUID.class),
                    result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                    toInstant(result.getTimestamp("deleted_at")));
    /** 草稿事实的唯一行映射。 */
    private static final RowMapper<ApplicationDraft> DRAFT_MAPPER = (result, row) ->
            new ApplicationDraft(result.getObject("application_id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    readJson(result, "content"), result.getLong("revision"),
                    result.getObject("updated_by", UUID.class), result.getTimestamp("created_at").toInstant(),
                    result.getTimestamp("updated_at").toInstant());
    /** 应用创建恢复映射不投影原始键或请求正文，数据库中也只持久化摘要。 */
    private static final RowMapper<ApplicationCreationResult> CREATION_RESULT_MAPPER = (result, row) ->
            new ApplicationCreationResult(
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("project_id", UUID.class),
                    result.getObject("account_id", UUID.class),
                    result.getString("idempotency_key_digest"),
                    result.getString("request_digest"),
                    result.getObject("application_id", UUID.class));
    /** 将单SQL分类与同次UPDATE RETURNING草稿映射为封闭领域结果。 */
    private static final RowMapper<ApplicationDraftSaveResult> DRAFT_SAVE_RESULT_MAPPER = (result, row) ->
            switch (result.getString("save_status")) {
                case "SAVED" -> ApplicationDraftSaveResult.saved(DRAFT_MAPPER.mapRow(result, row));
                case "NOT_FOUND" -> ApplicationDraftSaveResult.notFound();
                case "REVISION_CONFLICT" -> ApplicationDraftSaveResult.revisionConflict();
                case "REVISION_EXHAUSTED" -> ApplicationDraftSaveResult.revisionExhausted();
                case "DATABASE_INCONSISTENT" -> throw new DataIntegrityViolationException(
                        "活跃应用目录缺少唯一草稿事实");
                default -> throw new DataIntegrityViolationException("数据库返回未知草稿保存状态");
            };
    /** 不可变版本事实的唯一行映射。 */
    private static final RowMapper<ApplicationVersion> VERSION_MAPPER = (result, row) ->
            new ApplicationVersion(result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class), result.getObject("project_id", UUID.class),
                    result.getObject("application_id", UUID.class), result.getLong("version_number"),
                    result.getLong("source_draft_revision"), readJson(result, "snapshot"),
                    result.getString("snapshot_digest_algorithm"), result.getString("snapshot_digest"),
                    result.getObject("published_by_account_id", UUID.class),
                    result.getTimestamp("published_at").toInstant());

    /** 单语句历史分页的外连接行映射；空summary精确表示可见应用尚无版本。 */
    private static final RowMapper<VersionSummaryRow> VERSION_SUMMARY_ROW_MAPPER = (result, row) -> {
        UUID versionId = result.getObject("version_id", UUID.class);
        if (versionId == null) {
            return new VersionSummaryRow(null);
        }
        return new VersionSummaryRow(new ApplicationVersionSummary(
                versionId,
                result.getObject("version_tenant_id", UUID.class),
                result.getObject("version_project_id", UUID.class),
                result.getObject("version_application_id", UUID.class),
                result.getLong("version_number"),
                result.getLong("source_draft_revision"),
                result.getString("snapshot_digest_algorithm"),
                result.getString("snapshot_digest"),
                result.getTimestamp("published_at").toInstant()));
    };
    /** 管理详情单语句先由外层可见目录造行，再按版本列是否为空形成第二级分类。 */
    private static final RowMapper<ApplicationVersionLookupResult> MANAGEMENT_VERSION_MAPPER = (result, row) ->
            result.getObject("id", UUID.class) == null
                    ? ApplicationVersionLookupResult.versionNotFound()
                    : ApplicationVersionLookupResult.found(VERSION_MAPPER.mapRow(result, row));

    /** 已配置RLS上下文的数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建应用聚合JDBC适配器。
     *
     * @param jdbcTemplate 已配置项目RLS上下文的数据访问模板
     */
    public JdbcApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void create(ApplicationCatalogEntry application, ApplicationDraft draft) {
        requireInitialAggregate(application, draft);
        int createdDrafts = jdbcTemplate.update("""
                WITH created_application AS (
                    INSERT INTO app_application(
                        id, tenant_id, project_id, app_key, management_name,
                        publication_revision, current_version_id, created_by, updated_by,
                        created_at, updated_at, deleted_at)
                    VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL)
                    ON CONFLICT (app_key) DO NOTHING
                    RETURNING id, tenant_id, project_id
                )
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision,
                    updated_by, created_at, updated_at)
                SELECT created.id, created.tenant_id, created.project_id, ?::jsonb, 0, ?, ?, ?
                  FROM created_application created
                 WHERE created.id = ? AND created.tenant_id = ? AND created.project_id = ?
                """, application.id(), application.tenantId(), application.projectId(), application.appKey(),
                application.managementName(), application.createdBy(), application.updatedBy(),
                Timestamp.from(application.createdAt()), Timestamp.from(application.updatedAt()),
                draft.content().toString(), draft.updatedBy(), Timestamp.from(draft.createdAt()),
                Timestamp.from(draft.updatedAt()), draft.applicationId(), draft.tenantId(), draft.projectId());
        // (app_key)精确推断迁移中的app_application_app_key_uk；其他约束不匹配该目标并仍由PG原样拒绝。
        if (createdDrafts == 0) {
            throw new ApplicationKeyCollisionException();
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCreationRequest(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest) {
        requireCreationTransaction();
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(accountId, "accountId");
        requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        // 映射不存在时没有行可锁；64-bit摘要碰撞只扩大串行范围，不参与身份或请求相等判断。
        jdbcTemplate.queryForObject("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(
                    concat_ws(':', ?::text, ?::text, ?::text, ?), 12012::bigint))
                """, Integer.class, tenantId, projectId, accountId, idempotencyKeyDigest);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApplicationCreationResult> findCreationResult(
            UUID tenantId, UUID projectId, UUID accountId, String idempotencyKeyDigest) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(accountId, "accountId");
        requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        return jdbcTemplate.query("""
                SELECT tenant_id, project_id, account_id, idempotency_key_digest,
                       request_digest, application_id
                  FROM app_application_creation_result
                 WHERE tenant_id = ? AND project_id = ? AND account_id = ?
                   AND idempotency_key_digest = ?
                """, CREATION_RESULT_MAPPER,
                tenantId, projectId, accountId, idempotencyKeyDigest).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApplicationCatalogEntry> findCreationApplication(UUID projectId, UUID applicationId) {
        requireCreationTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, app_key, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM app_application
                 WHERE project_id = ? AND id = ?
                 FOR UPDATE
                """, APPLICATION_MAPPER, projectId, applicationId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createIdempotent(
            ApplicationCatalogEntry application,
            ApplicationDraft draft,
            ApplicationCreationResult creationResult) {
        requireInitialAggregate(application, draft);
        requireCreationResult(application, creationResult);
        int createdResults = jdbcTemplate.update("""
                WITH created_application AS (
                    INSERT INTO app_application(
                        id, tenant_id, project_id, app_key, management_name,
                        publication_revision, current_version_id, created_by, updated_by,
                        created_at, updated_at, deleted_at)
                    VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, NULL)
                    ON CONFLICT (app_key) DO NOTHING
                    RETURNING id, tenant_id, project_id
                ), created_draft AS (
                    INSERT INTO app_application_draft(
                        application_id, tenant_id, project_id, content, revision,
                        updated_by, created_at, updated_at)
                    SELECT created.id, created.tenant_id, created.project_id, ?::jsonb, 0, ?, ?, ?
                      FROM created_application created
                     WHERE created.id = ? AND created.tenant_id = ? AND created.project_id = ?
                    RETURNING application_id, tenant_id, project_id
                )
                INSERT INTO app_application_creation_result(
                    tenant_id, project_id, account_id, idempotency_key_digest,
                    request_digest, application_id, created_at)
                SELECT draft.tenant_id, draft.project_id, ?, ?, ?, draft.application_id, ?
                  FROM created_draft draft
                 WHERE draft.application_id = ? AND draft.tenant_id = ? AND draft.project_id = ?
                """, application.id(), application.tenantId(), application.projectId(), application.appKey(),
                application.managementName(), application.createdBy(), application.updatedBy(),
                Timestamp.from(application.createdAt()), Timestamp.from(application.updatedAt()),
                draft.content().toString(), draft.updatedBy(), Timestamp.from(draft.createdAt()),
                Timestamp.from(draft.updatedAt()), draft.applicationId(), draft.tenantId(), draft.projectId(),
                creationResult.accountId(), creationResult.idempotencyKeyDigest(), creationResult.requestDigest(),
                Timestamp.from(application.createdAt()), creationResult.applicationId(), creationResult.tenantId(),
                creationResult.projectId());
        // 仅appKey冲突能让整个CTE产生零映射；同键映射冲突表示锁或调用顺序被绕过，必须原样失败。
        if (createdResults == 0) {
            throw new ApplicationKeyCollisionException();
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ApplicationCatalogEntry> find(UUID projectId, UUID applicationId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, app_key, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM app_application
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, APPLICATION_MAPPER, projectId, applicationId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public CursorPage<ApplicationCatalogEntry> page(UUID projectId, String cursor, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        if (limit < 1 || limit > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用目录页大小必须在1至200之间");
        }
        CursorPosition position = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, project_id, app_key, management_name, publication_revision,
                       current_version_id, created_by, updated_by, created_at, updated_at, deleted_at
                  FROM app_application
                 WHERE project_id = ? AND deleted_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        if (position != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id > ?))");
            arguments.add(Timestamp.from(position.updatedAt()));
            arguments.add(Timestamp.from(position.updatedAt()));
            arguments.add(position.applicationId());
        }
        sql.append(" ORDER BY updated_at DESC, id ASC LIMIT ?");
        arguments.add(limit + 1);
        List<ApplicationCatalogEntry> rows = jdbcTemplate.query(
                sql.toString(), APPLICATION_MAPPER, arguments.toArray());
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<ApplicationCatalogEntry> items = List.copyOf(rows.subList(0, limit));
        ApplicationCatalogEntry last = items.getLast();
        return CursorPage.of(items, Cursor.encode(last.updatedAt() + "|" + last.id()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<CursorPage<ApplicationVersionSummary>> pageVersions(
            UUID projectId, UUID applicationId, String cursor, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        if (limit < 1 || limit > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用版本页大小必须在1至200之间");
        }
        Long position = decodeVersionCursor(cursor);
        String cursorClause = position == null ? "" : " AND candidate.version_number < ?";
        String sql = """
                WITH visible_application AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM app_application
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                )
                SELECT application.id AS visible_application_id,
                       version.id AS version_id,
                       version.tenant_id AS version_tenant_id,
                       version.project_id AS version_project_id,
                       version.application_id AS version_application_id,
                       version.version_number,
                       version.source_draft_revision,
                       version.snapshot_digest_algorithm,
                       version.snapshot_digest,
                       version.published_at
                  FROM visible_application application
                  LEFT JOIN LATERAL (
                      SELECT candidate.id, candidate.tenant_id, candidate.project_id,
                             candidate.application_id, candidate.version_number,
                             candidate.source_draft_revision, candidate.snapshot_digest_algorithm,
                             candidate.snapshot_digest, candidate.published_at
                        FROM app_application_version candidate
                       WHERE candidate.tenant_id = application.tenant_id
                         AND candidate.project_id = application.project_id
                         AND candidate.application_id = application.id
                """ + cursorClause + """
                       ORDER BY candidate.version_number DESC
                       LIMIT ?
                  ) version ON true
                 ORDER BY version.version_number DESC NULLS LAST
                """;
        List<Object> arguments = new ArrayList<>();
        arguments.add(projectId);
        arguments.add(applicationId);
        if (position != null) {
            arguments.add(position);
        }
        arguments.add(limit + 1);
        List<VersionSummaryRow> rows = jdbcTemplate.query(
                sql, VERSION_SUMMARY_ROW_MAPPER, arguments.toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<ApplicationVersionSummary> summaries = rows.stream()
                .map(VersionSummaryRow::summary)
                .filter(Objects::nonNull)
                .toList();
        if (summaries.size() <= limit) {
            return Optional.of(CursorPage.last(summaries));
        }
        List<ApplicationVersionSummary> items = List.copyOf(summaries.subList(0, limit));
        ApplicationVersionSummary last = items.getLast();
        return Optional.of(CursorPage.of(
                items, Cursor.encode(Long.toString(last.versionNumber()))));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean rename(UUID projectId, UUID applicationId, String managementName,
                          UUID updatedBy, Instant updatedAt) {
        Objects.requireNonNull(managementName, "managementName");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return jdbcTemplate.update("""
                UPDATE app_application
                   SET management_name = ?, updated_by = ?, updated_at = ?
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, managementName, updatedBy, Timestamp.from(updatedAt), projectId, applicationId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ApplicationDraft> findDraft(UUID projectId, UUID applicationId) {
        return jdbcTemplate.query("""
                SELECT draft.application_id, draft.tenant_id, draft.project_id, draft.content,
                       draft.revision, draft.updated_by, draft.created_at, draft.updated_at
                  FROM app_application_draft draft
                  JOIN app_application application
                    ON application.tenant_id = draft.tenant_id
                   AND application.project_id = draft.project_id
                   AND application.id = draft.application_id
                 WHERE draft.project_id = ? AND draft.application_id = ?
                   AND application.deleted_at IS NULL
                """, DRAFT_MAPPER, projectId, applicationId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ApplicationDraftSaveResult saveDraft(
            UUID projectId, UUID applicationId, long expectedRevision,
            JsonNode content, UUID updatedBy, Instant updatedAt) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision不得为负数");
        }
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // JsonNode可变；在执行SQL前冻结本次完整保存的单一序列化输入，避免同revision写入混合观察。
        String contentSnapshot = content.deepCopy().toString();
        return jdbcTemplate.queryForObject("""
                WITH live_application AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM app_application
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                     FOR UPDATE
                ), current_draft AS MATERIALIZED (
                    SELECT draft.application_id, draft.tenant_id, draft.project_id, draft.content,
                           draft.revision, draft.updated_by, draft.created_at, draft.updated_at
                      FROM app_application_draft draft
                      JOIN live_application application
                        ON application.tenant_id = draft.tenant_id
                       AND application.project_id = draft.project_id
                       AND application.id = draft.application_id
                ), saved AS (
                UPDATE app_application_draft draft
                   SET content = ?::jsonb, revision = draft.revision + 1,
                       updated_by = ?, updated_at = ?
                  FROM current_draft current
                 WHERE draft.tenant_id = current.tenant_id
                   AND draft.project_id = current.project_id
                   AND draft.application_id = current.application_id
                   AND draft.revision = current.revision
                   AND current.project_id = ? AND current.application_id = ?
                   AND current.revision = ? AND current.revision < 9223372036854775807
                RETURNING draft.application_id, draft.tenant_id, draft.project_id, draft.content,
                          draft.revision, draft.updated_by, draft.created_at, draft.updated_at
                ), classified AS (
                    SELECT CASE
                               WHEN EXISTS (SELECT 1 FROM saved) THEN 'SAVED'
                               WHEN NOT EXISTS (SELECT 1 FROM live_application) THEN 'NOT_FOUND'
                               WHEN NOT EXISTS (SELECT 1 FROM current_draft) THEN 'DATABASE_INCONSISTENT'
                               WHEN EXISTS (SELECT 1 FROM current_draft
                                             WHERE revision = 9223372036854775807)
                                   THEN 'REVISION_EXHAUSTED'
                               ELSE 'REVISION_CONFLICT'
                           END AS save_status
                )
                SELECT classified.save_status,
                       saved.application_id, saved.tenant_id, saved.project_id, saved.content,
                       saved.revision, saved.updated_by, saved.created_at, saved.updated_at
                  FROM classified
                  LEFT JOIN saved ON true
                """, DRAFT_SAVE_RESULT_MAPPER, projectId, applicationId, contentSnapshot, updatedBy,
                Timestamp.from(updatedAt), projectId, applicationId, expectedRevision);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ApplicationVersion> findVersion(UUID projectId, UUID applicationId, UUID versionId) {
        return queryVersion(" AND version.id = ?", projectId, applicationId, versionId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ApplicationVersionLookupResult findVersionForManagement(
            UUID projectId, UUID applicationId, UUID versionId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(versionId, "versionId");
        List<ApplicationVersionLookupResult> rows = jdbcTemplate.query("""
                WITH visible_application AS MATERIALIZED (
                    SELECT tenant_id, project_id, id
                      FROM app_application
                     WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                )
                SELECT version.id, version.tenant_id, version.project_id, version.application_id,
                       version.version_number, version.source_draft_revision, version.snapshot,
                       version.snapshot_digest_algorithm, version.snapshot_digest,
                       version.published_by_account_id, version.published_at
                  FROM visible_application application
                  LEFT JOIN LATERAL (
                      SELECT candidate.id, candidate.tenant_id, candidate.project_id,
                             candidate.application_id, candidate.version_number,
                             candidate.source_draft_revision, candidate.snapshot,
                             candidate.snapshot_digest_algorithm, candidate.snapshot_digest,
                             candidate.published_by_account_id, candidate.published_at
                        FROM app_application_version candidate
                       WHERE candidate.tenant_id = application.tenant_id
                         AND candidate.project_id = application.project_id
                         AND candidate.application_id = application.id
                         AND candidate.id = ?
                  ) version ON true
                """, MANAGEMENT_VERSION_MAPPER, projectId, applicationId, versionId);
        if (rows.isEmpty()) {
            return ApplicationVersionLookupResult.applicationNotFound();
        }
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("应用管理详情查询返回非唯一分类");
        }
        return rows.getFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ApplicationVersion> findLatestVersion(UUID projectId, UUID applicationId) {
        return queryVersion(" ORDER BY version.version_number DESC LIMIT 1", projectId, applicationId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ApplicationPublicationState> findPublicationState(UUID projectId, UUID applicationId) {
        return queryPublicationState(projectId, applicationId, "");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApplicationPublicationState> lockPublicationState(UUID projectId, UUID applicationId) {
        requirePublicationTransaction();
        requireReadCommittedSnapshot("应用发布状态锁");
        if (!acquirePublicationLock(projectId, applicationId)) {
            return Optional.empty();
        }
        // READ COMMITTED下等待行锁后用新语句快照读取历史最大号，保证指针、revision与版本聚合属于同一已提交状态。
        return queryPublicationState(projectId, applicationId, "");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationPublicationAppendResult appendPublication(
            ApplicationVersion version, long expectedPublicationRevision) {
        requirePublicationTransaction();
        Objects.requireNonNull(version, "version");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        ArrayNode references = applicationDashboardReferences(version.snapshot());
        List<ApplicationPublicationAppendResult> rows = jdbcTemplate.query("""
                SELECT publication_status, version_number, publication_revision
                  FROM public.application_publish_version(
                       ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?)
                """, (result, row) -> new ApplicationPublicationAppendResult(
                ApplicationPublicationAppendResult.Status.valueOf(result.getString("publication_status")),
                result.getObject("version_number", Long.class),
                result.getObject("publication_revision", Long.class)),
                version.projectId(), version.applicationId(), version.sourceDraftRevision(),
                expectedPublicationRevision, version.id(), version.snapshot().toString(),
                version.snapshotDigestAlgorithm(), version.snapshotDigest(), references.toString(),
                version.publishedByAccountId(), Timestamp.from(version.publishedAt()));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("应用受控发布入口未返回唯一结果");
        }
        return rows.getFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationPublicationRollbackResult rollbackPublication(
            UUID projectId,
            UUID applicationId,
            UUID targetVersionId,
            long expectedPublicationRevision,
            UUID updatedBy,
            Instant updatedAt) {
        requirePublicationTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(targetVersionId, "targetVersionId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<ApplicationPublicationRollbackResult> rows = jdbcTemplate.query("""
                SELECT rollback_status, publication_revision
                  FROM public.application_rollback_version(?, ?, ?, ?, ?, ?)
                """, (result, row) -> new ApplicationPublicationRollbackResult(
                ApplicationPublicationRollbackResult.Status.valueOf(result.getString("rollback_status")),
                result.getObject("publication_revision", Long.class)),
                projectId, applicationId, targetVersionId, expectedPublicationRevision,
                updatedBy, Timestamp.from(updatedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("应用受控回滚入口未返回唯一结果");
        }
        return rows.getFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationPublicationWithdrawalResult withdrawPublication(
            UUID projectId,
            UUID applicationId,
            long expectedPublicationRevision,
            UUID updatedBy,
            Instant updatedAt) {
        requirePublicationTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<ApplicationPublicationWithdrawalResult> rows = jdbcTemplate.query("""
                SELECT withdrawal_status, previous_version_id, publication_revision
                  FROM public.application_withdraw_publication(?, ?, ?, ?, ?)
                """, (result, row) -> new ApplicationPublicationWithdrawalResult(
                ApplicationPublicationWithdrawalResult.Status.valueOf(
                        result.getString("withdrawal_status")),
                result.getObject("previous_version_id", UUID.class),
                result.getObject("publication_revision", Long.class)),
                projectId, applicationId, expectedPublicationRevision,
                updatedBy, Timestamp.from(updatedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("应用受控撤回入口未返回唯一结果");
        }
        return rows.getFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationSoftDeleteResult softDelete(
            UUID projectId,
            UUID applicationId,
            long expectedPublicationRevision,
            UUID updatedBy,
            Instant deletedAt) {
        requirePublicationTransaction();
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(deletedAt, "deletedAt");
        if (expectedPublicationRevision < 0) {
            throw new IllegalArgumentException("expectedPublicationRevision不得为负数");
        }
        List<ApplicationSoftDeleteResult> rows = jdbcTemplate.query("""
                SELECT deletion_status, previous_version_id, publication_revision, deleted_at
                  FROM public.application_soft_delete(?, ?, ?, ?, ?)
                """, (result, row) -> new ApplicationSoftDeleteResult(
                ApplicationSoftDeleteResult.Status.valueOf(result.getString("deletion_status")),
                result.getObject("previous_version_id", UUID.class),
                result.getObject("publication_revision", Long.class),
                toInstant(result.getTimestamp("deleted_at"))),
                projectId, applicationId, expectedPublicationRevision,
                updatedBy, Timestamp.from(deletedAt));
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("应用受控软删入口未返回唯一结果");
        }
        ApplicationSoftDeleteResult result = rows.getFirst();
        if (result.status() == ApplicationSoftDeleteResult.Status.DELETED
                && result.observedPublicationRevision().orElseThrow()
                != expectedPublicationRevision + 1) {
            throw new DataIntegrityViolationException("应用受控软删入口推进了非预期发布revision");
        }
        return result;
    }

    /** 先只取得应用目录与草稿锁，避免锁等待前的语句快照污染历史版本聚合。 */
    private boolean acquirePublicationLock(UUID projectId, UUID applicationId) {
        return !jdbcTemplate.query("""
                SELECT application.id
                  FROM app_application application
                  JOIN app_application_draft draft
                    ON draft.tenant_id = application.tenant_id
                   AND draft.project_id = application.project_id
                   AND draft.application_id = application.id
                 WHERE application.project_id = ? AND application.id = ?
                 FOR UPDATE OF application, draft
                """, (result, row) -> result.getObject("id", UUID.class), projectId, applicationId).isEmpty();
    }

    /** 查询应用发布状态；固定尾段只由仓储内部选择普通读取。 */
    private Optional<ApplicationPublicationState> queryPublicationState(
            UUID projectId, UUID applicationId, String suffix) {
        return jdbcTemplate.query("""
                SELECT application.id, application.tenant_id, application.project_id,
                       draft.revision AS draft_revision, application.publication_revision,
                       application.current_version_id, application.deleted_at,
                       COALESCE((SELECT MAX(version.version_number)
                                   FROM app_application_version version
                                  WHERE version.tenant_id = application.tenant_id
                                    AND version.project_id = application.project_id
                                    AND version.application_id = application.id), 0) AS latest_version_number
                  FROM app_application application
                  JOIN app_application_draft draft
                    ON draft.tenant_id = application.tenant_id
                   AND draft.project_id = application.project_id
                   AND draft.application_id = application.id
                 WHERE application.project_id = ? AND application.id = ?
                """ + suffix, (result, row) -> new ApplicationPublicationState(
                        result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                        result.getObject("project_id", UUID.class), result.getLong("draft_revision"),
                        result.getLong("publication_revision"),
                        result.getObject("current_version_id", UUID.class),
                        result.getLong("latest_version_number"),
                        toInstant(result.getTimestamp("deleted_at"))),
                projectId, applicationId).stream().findFirst();
    }

    /** 从不可变快照投影受控函数参数；数据库仍会与锁内草稿和精确目标逐项复核。 */
    private static ArrayNode applicationDashboardReferences(JsonNode snapshot) {
        JsonNode source = snapshot.path("dashboardRefs");
        if (!source.isArray() || source.isEmpty() || source.size() > 5) {
            throw new IllegalArgumentException("应用版本快照必须包含1至5个看板引用");
        }
        ArrayNode references = JSON_MAPPER.createArrayNode();
        for (int position = 0; position < source.size(); position++) {
            JsonNode reference = source.get(position);
            references.addObject()
                    .put("position", position)
                    .put("dashboardId", reference.path("dashboardId").asString())
                    .put("dashboardVersionId", reference.path("dashboardVersionId").asString());
        }
        return references;
    }

    /** 查询一个精确版本或应用内最大版本；固定SQL片段不接收外部输入。 */
    private Optional<ApplicationVersion> queryVersion(String suffix, Object... arguments) {
        return jdbcTemplate.query("""
                SELECT version.id, version.tenant_id, version.project_id, version.application_id,
                       version.version_number, version.source_draft_revision, version.snapshot,
                       version.snapshot_digest_algorithm, version.snapshot_digest,
                       version.published_by_account_id, version.published_at
                  FROM app_application_version version
                  JOIN app_application application
                    ON application.tenant_id = version.tenant_id
                   AND application.project_id = version.project_id
                   AND application.id = version.application_id
                 WHERE version.project_id = ? AND version.application_id = ?
                   AND application.deleted_at IS NULL
                """ + suffix, VERSION_MAPPER, arguments).stream().findFirst();
    }

    /** 将不可信游标严格解码为目录复合排序位置。 */
    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("应用目录游标字段不完整");
            }
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用目录分页游标无效");
        }
    }

    /** 将不可信历史游标严格解码为正版本号；零和负数从未是合法页位置。 */
    private static Long decodeVersionCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = Cursor.decode(cursor);
            if (!decoded.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("应用版本游标必须是规范正整数");
            }
            return Long.parseLong(decoded);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用版本分页游标无效");
        }
    }

    /** 创建必须同时满足初始目录、草稿和三轴归属，不让单语句悄悄产生零草稿目录。 */
    private static void requireInitialAggregate(ApplicationCatalogEntry application, ApplicationDraft draft) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(draft, "draft");
        if (application.publicationRevision() != 0 || application.currentVersionId() != null
                || application.deletedAt() != null || draft.revision() != 0
                || !application.id().equals(draft.applicationId())
                || !application.tenantId().equals(draft.tenantId())
                || !application.projectId().equals(draft.projectId())) {
            throw new IllegalArgumentException("创建应用必须提供同归属的未发布目录与revision 0草稿");
        }
    }

    /** 创建恢复映射必须精确指向同一初始目录，防止调用方把其他请求身份绑定到新应用。 */
    private static void requireCreationResult(
            ApplicationCatalogEntry application, ApplicationCreationResult creationResult) {
        Objects.requireNonNull(creationResult, "creationResult");
        if (!application.tenantId().equals(creationResult.tenantId())
                || !application.projectId().equals(creationResult.projectId())
                || !application.createdBy().equals(creationResult.accountId())
                || !application.id().equals(creationResult.applicationId())) {
            throw new IllegalArgumentException("应用创建恢复映射必须与初始目录身份一致");
        }
    }

    /** 摘要在进入SQL前仍做防御校验，避免char(64)填充或截断产生伪相等。 */
    private static void requireDigest(String digest, String field) {
        Objects.requireNonNull(digest, field);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + "必须是SHA-256小写十六进制");
        }
    }

    /** 将数据库JSONB文本解析成隔离树；数据库若出现不可解析事实则保留为SQL映射失败。 */
    private static JsonNode readJson(ResultSet result, String column) throws SQLException {
        try {
            return JSON_READER.readTree(result.getString(column));
        } catch (JacksonException exception) {
            throw new SQLException("应用JSONB事实无法解析", exception);
        }
    }

    /** PostgreSQL可空timestamptz到Instant的唯一转换。 */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 发布专用入口必须加入已有可写事务，避免锁在方法返回时提前释放。 */
    private static void requirePublicationTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("应用发布仓储入口必须加入已有可写事务");
        }
    }

    /** 创建advisory与恢复目录锁必须加入服务已有可写事务，保证锁覆盖映射判断、创建和审计。 */
    private static void requireCreationTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("应用幂等创建仓储入口必须加入已有可写事务");
        }
    }

    /** 两语句锁后重读依赖每条语句取得新快照；RR/Serializable无法满足该并发前置，必须在加锁前拒绝。 */
    private void requireReadCommittedSnapshot(String operation) {
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException(operation + "要求READ COMMITTED或READ UNCOMMITTED事务");
        }
    }

    /** @param updatedAt 上一页末项更新时间 @param applicationId 上一页末项稳定应用ID */
    private record CursorPosition(Instant updatedAt, UUID applicationId) {
    }

    /** @param summary 轻量版本元数据；可见应用没有任何版本时为空 */
    private record VersionSummaryRow(ApplicationVersionSummary summary) {
    }
}
