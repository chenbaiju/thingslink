package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.application.publication.PostgreSqlApplicationSnapshotCanonicalForm;
import com.things.link.dashboard.application.publication.PostgreSqlDashboardSchemaCanonicalForm;
import com.things.link.dashboard.domain.ApplicationRuntimeSchemaRepository;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.RuntimeDashboardSchemaProjection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用单条普通项目RLS查询读取应用当前上下文中的一个精确Dashboard Schema。
 *
 * <p>应用、当前版本、发布代次、快照、精确关系、目标版本和目录状态属于同一语句快照；查询只读取
 * 目标Schema正文。格式、摘要和字节边界不进入WHERE，避免损坏事实被静默解释为资源不存在。</p>
 */
@Repository
public class JdbcApplicationRuntimeSchemaRepository implements ApplicationRuntimeSchemaRepository {

    /** 数据库JSONB文本只在持久适配器内解释。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** PostgreSQL规范Schema文本的冻结上限，等于500KiB。 */
    private static final int MAXIMUM_SCHEMA_BYTES = 512_000;

    /**
     * LEFT JOIN保留缺目标关系或损坏父版本的诊断行；相关子查询只聚合目标版本的模型关系。
     * 稳定看板current只提供当前可运行布尔值，绝不替换请求的精确历史版本。
     */
    private static final String FIND_SCHEMA_SQL = """
            SELECT application.tenant_id,
                   application.project_id,
                   application.id AS application_id,
                   application.app_key,
                   application.publication_revision,
                   version.id AS application_version_id,
                   version.snapshot::text AS application_snapshot,
                   version.snapshot_digest_algorithm AS application_snapshot_digest_algorithm,
                   version.snapshot_digest AS application_snapshot_digest,
                   encode(digest(convert_to(version.snapshot::text, 'UTF8'), 'sha256'), 'hex')
                       AS calculated_application_snapshot_digest,
                   COALESCE((
                       SELECT jsonb_agg(jsonb_build_object(
                                  'position', application_reference.position,
                                  'dashboardId', application_reference.dashboard_id,
                                  'dashboardVersionId', application_reference.dashboard_version_id)
                                  ORDER BY application_reference.position)
                         FROM public.app_application_version_dashboard_ref application_reference
                        WHERE application_reference.tenant_id = version.tenant_id
                          AND application_reference.project_id = version.project_id
                          AND application_reference.application_id = version.application_id
                          AND application_reference.application_version_id = version.id
                   ), '[]'::jsonb)::text AS application_references,
                   reference.position AS reference_position,
                   reference.dashboard_id AS reference_dashboard_id,
                   reference.dashboard_version_id AS reference_dashboard_version_id,
                   dashboard.id AS catalog_dashboard_id,
                   dashboard.deleted_at AS dashboard_deleted_at,
                   dashboard.current_version_id AS dashboard_current_version_id,
                   dashboard_version.id AS dashboard_version_id,
                   dashboard_version.dashboard_id,
                   dashboard_version.version_number AS dashboard_version_number,
                   dashboard_version.source_draft_revision,
                   dashboard_version.schema::text AS dashboard_schema,
                   dashboard_version.schema_version,
                   dashboard_version.schema_digest_algorithm,
                   dashboard_version.schema_digest,
                   encode(digest(convert_to(dashboard_version.schema::text, 'UTF8'), 'sha256'), 'hex')
                       AS calculated_dashboard_schema_digest,
                   octet_length(dashboard_version.schema::text) AS dashboard_schema_bytes,
                   dashboard_version.required_components::text AS required_components,
                   dashboard_version.required_resources::text AS required_resources,
                   dashboard_version.published_by_account_id,
                   dashboard_version.published_at,
                   COALESCE((
                       SELECT jsonb_agg(jsonb_build_object(
                                  'position', model_reference.position,
                                  'modelKey', model_reference.model_key,
                                  'thingModelVersionId', model_reference.thing_model_version_id)
                                  ORDER BY model_reference.position)
                         FROM public.dash_dashboard_version_model_ref model_reference
                        WHERE model_reference.tenant_id = dashboard_version.tenant_id
                          AND model_reference.project_id = dashboard_version.project_id
                          AND model_reference.dashboard_id = dashboard_version.dashboard_id
                          AND model_reference.dashboard_version_id = dashboard_version.id
                   ), '[]'::jsonb)::text AS model_references
              FROM public.app_application application
              LEFT JOIN public.app_application_version version
                ON version.tenant_id = application.tenant_id
               AND version.project_id = application.project_id
               AND version.application_id = application.id
               AND version.id = application.current_version_id
              LEFT JOIN public.app_application_version_dashboard_ref reference
                ON reference.tenant_id = version.tenant_id
               AND reference.project_id = version.project_id
               AND reference.application_id = version.application_id
               AND reference.application_version_id = version.id
               AND reference.dashboard_version_id = ?
              LEFT JOIN public.dash_dashboard_version dashboard_version
                ON dashboard_version.tenant_id = reference.tenant_id
               AND dashboard_version.project_id = reference.project_id
               AND dashboard_version.dashboard_id = reference.dashboard_id
               AND dashboard_version.id = reference.dashboard_version_id
              LEFT JOIN public.dash_dashboard dashboard
                ON dashboard.tenant_id = reference.tenant_id
               AND dashboard.project_id = reference.project_id
               AND dashboard.id = reference.dashboard_id
             WHERE application.tenant_id = ?
               AND application.project_id = ?
               AND application.app_key = ?
               AND application.deleted_at IS NULL
               AND application.current_version_id = ?
               AND application.publication_revision = ?
            """;

    /** 调用方事务绑定的普通APP JDBC入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建运行Schema持久适配器。
     *
     * @param jdbcTemplate 事务感知JDBC入口
     */
    public JdbcApplicationRuntimeSchemaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<RuntimeDashboardSchemaProjection> findSchema(
            UUID tenantId,
            UUID projectId,
            String appKey,
            UUID applicationVersionId,
            long expectedPublicationRevision,
            UUID dashboardVersionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        List<SchemaRow> rows = jdbcTemplate.query(FIND_SCHEMA_SQL,
                JdbcApplicationRuntimeSchemaRepository::mapRow,
                dashboardVersionId, tenantId, projectId, appKey,
                applicationVersionId, expectedPublicationRevision);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("运行Schema查询返回重复应用事实");
        }
        try {
            return toProjection(rows.getFirst(), dashboardVersionId);
        } catch (DataIntegrityViolationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DataIntegrityViolationException("运行Schema持久事实损坏", exception);
        }
    }

    /** 将单行数据库结果保留为未解释事实，随后按完整应用快照核对目标关系。 */
    private static SchemaRow mapRow(ResultSet result, int rowNumber) throws SQLException {
        return new SchemaRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("application_id", UUID.class),
                result.getString("app_key"),
                result.getLong("publication_revision"),
                result.getObject("application_version_id", UUID.class),
                result.getString("application_snapshot"),
                result.getString("application_snapshot_digest_algorithm"),
                result.getString("application_snapshot_digest"),
                result.getString("calculated_application_snapshot_digest"),
                result.getString("application_references"),
                result.getObject("reference_position", Short.class),
                result.getObject("reference_dashboard_id", UUID.class),
                result.getObject("reference_dashboard_version_id", UUID.class),
                result.getObject("catalog_dashboard_id", UUID.class),
                result.getTimestamp("dashboard_deleted_at") != null,
                result.getObject("dashboard_current_version_id", UUID.class),
                result.getObject("dashboard_version_id", UUID.class),
                result.getObject("dashboard_id", UUID.class),
                result.getObject("dashboard_version_number", Long.class),
                result.getObject("source_draft_revision", Long.class),
                result.getString("dashboard_schema"),
                result.getString("schema_version"),
                result.getString("schema_digest_algorithm"),
                result.getString("schema_digest"),
                result.getString("calculated_dashboard_schema_digest"),
                result.getObject("dashboard_schema_bytes", Integer.class),
                result.getString("required_components"),
                result.getString("required_resources"),
                result.getObject("published_by_account_id", UUID.class),
                toInstant(result),
                result.getString("model_references"));
    }

    /** 完整复核应用快照后区分“未引用”与“声明引用但关系缺失”，避免损坏被压成404。 */
    private static Optional<RuntimeDashboardSchemaProjection> toProjection(
            SchemaRow row, UUID requestedDashboardVersionId) {
        require(PostgreSqlApplicationSnapshotCanonicalForm.DIGEST_ALGORITHM
                        .equals(row.applicationSnapshotDigestAlgorithm())
                        && row.applicationSnapshotDigest() != null
                        && row.applicationSnapshotDigest().equals(row.calculatedApplicationSnapshotDigest()),
                "当前应用版本快照摘要不一致");
        JdbcApplicationCurrentRepository.ParsedSnapshot snapshot =
                JdbcApplicationCurrentRepository.parseSnapshot(row.applicationSnapshot());
        requireExactApplicationReferences(snapshot, row.applicationReferences());
        List<JdbcApplicationCurrentRepository.ParsedReference> matches = snapshot.references().stream()
                .filter(reference -> requestedDashboardVersionId.equals(reference.dashboardVersionId()))
                .toList();
        require(matches.size() <= 1, "应用快照重复引用同一看板版本");
        if (matches.isEmpty()) {
            require(row.referenceDashboardVersionId() == null, "应用关系存在但快照未声明目标看板版本");
            return Optional.empty();
        }
        JdbcApplicationCurrentRepository.ParsedReference reference = matches.getFirst();
        int snapshotPosition = snapshot.references().indexOf(reference);
        require(row.referencePosition() != null && row.referencePosition() == snapshotPosition
                        && reference.dashboardId().equals(row.referenceDashboardId())
                        && requestedDashboardVersionId.equals(row.referenceDashboardVersionId()),
                "应用快照目标引用与持久关系不一致");
        require(row.catalogDashboardId() != null
                        && row.dashboardVersionId() != null
                        && row.dashboardVersionNumber() != null
                        && row.sourceDraftRevision() != null
                        && row.schemaBytes() != null
                        && row.publishedByAccountId() != null
                        && row.publishedAt() != null,
                "应用引用的看板目录或精确版本缺失");
        require(reference.dashboardId().equals(row.catalogDashboardId())
                        && reference.dashboardId().equals(row.dashboardId())
                        && reference.dashboardVersionId().equals(row.dashboardVersionId())
                        && reference.dashboardVersionNumber() == row.dashboardVersionNumber()
                        && reference.schemaVersion().equals(row.schemaVersion())
                        && reference.schemaDigestAlgorithm().equals(row.schemaDigestAlgorithm())
                        && reference.schemaDigest().equals(row.schemaDigest()),
                "应用快照引用与精确看板版本元数据不一致");
        require(row.schemaBytes() > 0 && row.schemaBytes() <= MAXIMUM_SCHEMA_BYTES,
                "Dashboard Schema超过500KiB冻结边界");
        require(PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM.equals(row.schemaDigestAlgorithm())
                        && row.schemaDigest() != null
                        && row.schemaDigest().equals(row.calculatedSchemaDigest()),
                "Dashboard Schema摘要不一致");
        DashboardVersion version = new DashboardVersion(
                row.dashboardVersionId(), row.tenantId(), row.projectId(), row.dashboardId(),
                row.dashboardVersionNumber(), row.sourceDraftRevision(), readJson(row.schema(), "Dashboard Schema"),
                row.schemaVersion(), row.schemaDigestAlgorithm(), row.schemaDigest(),
                readJson(row.requiredComponents(), "Dashboard组件清单"),
                readJson(row.requiredResources(), "Dashboard资源清单"),
                row.publishedByAccountId(), row.publishedAt(), readModelReferences(row.modelReferences()));
        boolean runnable = !row.dashboardDeleted() && row.dashboardCurrentVersionId() != null;
        return Optional.of(new RuntimeDashboardSchemaProjection(
                row.tenantId(), row.projectId(), row.applicationId(), row.appKey(), row.applicationVersionId(),
                row.publicationRevision(), runnable, version, reference.pages(), row.schemaBytes()));
    }

    /** 全量核对应用版本的有界关系身份，不读取其他看板Schema正文。 */
    private static void requireExactApplicationReferences(
            JdbcApplicationCurrentRepository.ParsedSnapshot snapshot, String source) {
        JsonNode relationships = readJson(source, "应用看板关系");
        require(relationships.isArray() && relationships.size() == snapshot.references().size(),
                "应用版本关系数量与快照不一致");
        for (int index = 0; index < relationships.size(); index++) {
            JsonNode relationship = relationships.get(index);
            JdbcApplicationCurrentRepository.ParsedReference reference = snapshot.references().get(index);
            require(relationship.isObject()
                            && relationship.size() == 3
                            && relationship.path("position").canConvertToInt()
                            && relationship.path("position").asInt() == index
                            && reference.dashboardId().toString().equals(relationship.path("dashboardId").asString())
                            && reference.dashboardVersionId().toString()
                            .equals(relationship.path("dashboardVersionId").asString()),
                    "应用版本关系身份或顺序与快照不一致");
        }
    }

    /** 把聚合模型关系恢复为按position排序的精确领域值。 */
    private static List<DashboardModelReference> readModelReferences(String source) {
        JsonNode values = readJson(source, "Dashboard模型关系");
        require(values.isArray(), "Dashboard模型关系必须是数组");
        List<DashboardModelReference> references = new ArrayList<>(values.size());
        int expectedPosition = 0;
        for (JsonNode value : values) {
            require(value.isObject()
                            && value.size() == 3
                            && value.path("position").canConvertToInt()
                            && value.path("modelKey").isString()
                            && value.path("thingModelVersionId").isString(),
                    "Dashboard模型关系聚合形状损坏");
            int position = value.path("position").asInt();
            require(position == expectedPosition++, "Dashboard模型关系位置不连续");
            references.add(new DashboardModelReference(
                    position, value.path("modelKey").asString(),
                    UUID.fromString(value.path("thingModelVersionId").asString())));
        }
        return List.copyOf(references);
    }

    /** 解析数据库JSONB文本；不可解析事实按完整性错误保留。 */
    private static JsonNode readJson(String source, String name) {
        try {
            return JSON.readTree(source);
        } catch (JacksonException exception) {
            throw new DataIntegrityViolationException(name + "无法解析", exception);
        }
    }

    /** 映射可空发布时间，缺值由后续完整性检查统一报告。 */
    private static Instant toInstant(ResultSet result) throws SQLException {
        return result.getTimestamp("published_at") == null
                ? null : result.getTimestamp("published_at").toInstant();
    }

    /** 将持久不变量失败稳定映射为数据库完整性异常。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new DataIntegrityViolationException(message);
        }
    }

    /** 单行数据库观察；目标字段可空以区分未引用、运行状态和持久损坏。 */
    private record SchemaRow(
            UUID tenantId,
            UUID projectId,
            UUID applicationId,
            String appKey,
            long publicationRevision,
            UUID applicationVersionId,
            String applicationSnapshot,
            String applicationSnapshotDigestAlgorithm,
            String applicationSnapshotDigest,
            String calculatedApplicationSnapshotDigest,
            String applicationReferences,
            Short referencePosition,
            UUID referenceDashboardId,
            UUID referenceDashboardVersionId,
            UUID catalogDashboardId,
            boolean dashboardDeleted,
            UUID dashboardCurrentVersionId,
            UUID dashboardVersionId,
            UUID dashboardId,
            Long dashboardVersionNumber,
            Long sourceDraftRevision,
            String schema,
            String schemaVersion,
            String schemaDigestAlgorithm,
            String schemaDigest,
            String calculatedSchemaDigest,
            Integer schemaBytes,
            String requiredComponents,
            String requiredResources,
            UUID publishedByAccountId,
            Instant publishedAt,
            String modelReferences) {
    }
}
