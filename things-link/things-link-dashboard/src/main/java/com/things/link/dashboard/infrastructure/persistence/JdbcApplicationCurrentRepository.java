package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.application.publication.PostgreSqlApplicationSnapshotCanonicalForm;
import com.things.link.dashboard.application.publication.PostgreSqlDashboardSchemaCanonicalForm;
import com.things.link.dashboard.domain.ApplicationRuntimeCurrentRepository;
import com.things.link.dashboard.domain.CurrentApplicationRuntimeProjection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用一次普通项目RLS查询恢复当前应用及其可运行看板引用。
 *
 * <p>S12-2a4a要求应用指针、不可变快照、精确关系和看板目录状态属于同一数据库语句快照。
 * 查询刻意保留已撤回或软删的看板目录行用于完整性复核，映射完成后才过滤运行资格；任何摘要、
 * 关系或版本元数据漂移均抛数据库完整性异常，不能静默成为404或少一项引用。</p>
 */
@Repository
public class JdbcApplicationCurrentRepository implements ApplicationRuntimeCurrentRepository {

    /** 数据库JSONB文本只在持久适配器内部解释，不向跨域端口泄露可变树。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 应用快照根字段闭集。 */
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "formatVersion", "displayName", "hostCompatibility", "dashboardRefs",
            "entryDashboardId", "requiredSchemas", "requiredComponents", "requiredResources");
    /** 宿主范围字段闭集。 */
    private static final Set<String> HOST_FIELDS = Set.of("minInclusive", "maxExclusive");
    /** 精确看板引用字段闭集。 */
    private static final Set<String> REFERENCE_FIELDS = Set.of(
            "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title",
            "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
    /** 页面导航字段闭集。 */
    private static final Set<String> PAGE_FIELDS = Set.of("id", "title");
    /** 组件聚合字段闭集。 */
    private static final Set<String> COMPONENT_FIELDS = Set.of("kind", "componentVersion");
    /** 资源聚合字段闭集。 */
    private static final Set<String> RESOURCE_FIELDS = Set.of("resourceId", "digest");
    /** 规范正Long字符串，拒绝前导零和溢出。 */
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");
    /** SHA-256小写十六进制语法。 */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** 三段无前导零SemVer语法。 */
    private static final Pattern SEMVER = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

    /**
     * 一条语句封闭应用current与全部精确关系；LEFT JOIN让关系或目标缺失保持可诊断的损坏行。
     * 看板current只判断稳定目录当前是否可运行，绝不替换应用已经固定的dashboard_version_id。
     */
    private static final String FIND_CURRENT_SQL = """
            SELECT application.tenant_id,
                   application.project_id,
                   application.id AS application_id,
                   application.app_key,
                   application.publication_revision,
                   version.id AS application_version_id,
                   version.version_number AS application_version_number,
                   version.snapshot::text AS application_snapshot,
                   version.snapshot_digest_algorithm,
                   version.snapshot_digest,
                   encode(digest(convert_to(version.snapshot::text, 'UTF8'), 'sha256'), 'hex')
                       AS calculated_application_snapshot_digest,
                   reference.position AS reference_position,
                   reference.dashboard_id AS reference_dashboard_id,
                   reference.dashboard_version_id AS reference_dashboard_version_id,
                   dashboard_version.version_number AS dashboard_version_number,
                   dashboard_version.schema_version,
                   dashboard_version.schema_digest_algorithm,
                   dashboard_version.schema_digest,
                   encode(digest(convert_to(dashboard_version.schema::text, 'UTF8'), 'sha256'), 'hex')
                       AS calculated_dashboard_schema_digest,
                   dashboard.id AS catalog_dashboard_id,
                   dashboard.deleted_at AS dashboard_deleted_at,
                   dashboard.current_version_id AS dashboard_current_version_id
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
               AND application.current_version_id IS NOT NULL
             ORDER BY reference.position
            """;

    /** 事务感知JDBC入口，调用方负责先建立可信二轴RLS。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建当前应用封闭查询适配器。
     *
     * @param jdbcTemplate 事务感知JDBC入口
     */
    public JdbcApplicationCurrentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<CurrentApplicationRuntimeProjection> findCurrent(
            UUID tenantId, UUID projectId, String appKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(appKey, "appKey");
        List<RuntimeRow> rows = jdbcTemplate.query(
                FIND_CURRENT_SQL, JdbcApplicationCurrentRepository::mapRow, tenantId, projectId, appKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toProjection(rows));
        } catch (DataIntegrityViolationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DataIntegrityViolationException("当前应用运行事实损坏", exception);
        }
    }

    /** 把单行SQL结果保留为未解释事实，聚合阶段才能比较整组关系与快照顺序。 */
    private static RuntimeRow mapRow(ResultSet result, int rowNumber) throws SQLException {
        return new RuntimeRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("application_id", UUID.class),
                result.getString("app_key"),
                result.getLong("publication_revision"),
                result.getObject("application_version_id", UUID.class),
                result.getLong("application_version_number"),
                result.getString("application_snapshot"),
                result.getString("snapshot_digest_algorithm"),
                result.getString("snapshot_digest"),
                result.getString("calculated_application_snapshot_digest"),
                result.getObject("reference_position", Short.class),
                result.getObject("reference_dashboard_id", UUID.class),
                result.getObject("reference_dashboard_version_id", UUID.class),
                result.getObject("dashboard_version_number", Long.class),
                result.getString("schema_version"),
                result.getString("schema_digest_algorithm"),
                result.getString("schema_digest"),
                result.getString("calculated_dashboard_schema_digest"),
                result.getObject("catalog_dashboard_id", UUID.class),
                result.getTimestamp("dashboard_deleted_at") != null,
                result.getObject("dashboard_current_version_id", UUID.class));
    }

    /** 恢复应用快照并逐位置核对不可变关系和版本元数据，最后过滤目录运行状态。 */
    private static CurrentApplicationRuntimeProjection toProjection(List<RuntimeRow> rows) {
        RuntimeRow first = rows.getFirst();
        require(first.publicationRevision() > 0 && first.applicationVersionNumber() > 0,
                "当前应用发布代次或版本号无效");
        require(PostgreSqlApplicationSnapshotCanonicalForm.DIGEST_ALGORITHM
                        .equals(first.snapshotDigestAlgorithm())
                        && first.snapshotDigest() != null
                        && first.snapshotDigest().equals(first.calculatedSnapshotDigest()),
                "当前应用版本快照摘要不一致");
        ParsedSnapshot snapshot = parseSnapshot(first.snapshot());
        require(rows.size() == snapshot.references().size(), "应用版本关系数量与快照不一致");
        List<CurrentApplicationRuntimeProjection.DashboardReference> runnable = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            RuntimeRow row = rows.get(index);
            ParsedReference reference = snapshot.references().get(index);
            requireSameApplicationRow(first, row);
            require(row.referencePosition() != null && row.referencePosition() == index,
                    "应用版本关系位置不连续");
            require(reference.dashboardId().equals(row.referenceDashboardId())
                            && reference.dashboardVersionId().equals(row.referenceDashboardVersionId()),
                    "应用版本关系身份与快照不一致");
            require(row.catalogDashboardId() != null && row.dashboardVersionNumber() != null,
                    "应用精确引用的看板目录或版本缺失");
            require(reference.dashboardVersionNumber() == row.dashboardVersionNumber()
                            && reference.schemaVersion().equals(row.schemaVersion())
                            && reference.schemaDigestAlgorithm().equals(row.schemaDigestAlgorithm())
                            && reference.schemaDigest().equals(row.schemaDigest())
                            && reference.schemaDigest().equals(row.calculatedSchemaDigest()),
                    "应用快照与精确看板版本元数据不一致");
            if (!row.dashboardDeleted() && row.dashboardCurrentVersionId() != null) {
                runnable.add(reference.toProjection());
            }
        }
        return new CurrentApplicationRuntimeProjection(
                first.tenantId(), first.projectId(), first.applicationId(), first.appKey(),
                snapshot.displayName(), first.publicationRevision(), first.applicationVersionId(),
                first.applicationVersionNumber(), snapshot.formatVersion(), snapshot.minimumHostVersionInclusive(),
                snapshot.maximumHostVersionExclusive(), snapshot.entryDashboardId(), runnable);
    }

    /** 同一SQL组中的应用公共字段必须完全相同，避免聚合代码吞掉意外重复身份。 */
    private static void requireSameApplicationRow(RuntimeRow expected, RuntimeRow actual) {
        require(expected.tenantId().equals(actual.tenantId())
                        && expected.projectId().equals(actual.projectId())
                        && expected.applicationId().equals(actual.applicationId())
                        && expected.appKey().equals(actual.appKey())
                        && expected.publicationRevision() == actual.publicationRevision()
                        && expected.applicationVersionId().equals(actual.applicationVersionId())
                        && expected.applicationVersionNumber() == actual.applicationVersionNumber()
                        && expected.snapshot().equals(actual.snapshot())
                        && Objects.equals(expected.snapshotDigestAlgorithm(), actual.snapshotDigestAlgorithm())
                        && Objects.equals(expected.snapshotDigest(), actual.snapshotDigest())
                        && Objects.equals(expected.calculatedSnapshotDigest(), actual.calculatedSnapshotDigest()),
                "当前应用查询返回混合版本事实");
    }

    /** 严格解释完整tc.application/v1快照，但只保留运行端口需要的字段。 */
    static ParsedSnapshot parseSnapshot(String source) {
        try {
            JsonNode tree = JSON.readTree(source);
            ObjectNode snapshot = requireObject(tree, "$snapshot");
            requireExactFields(snapshot, SNAPSHOT_FIELDS, "$snapshot");
            String formatVersion = requireString(snapshot, "formatVersion", "$snapshot");
            require("tc.application/v1".equals(formatVersion), "应用快照格式未登记");
            String displayName = requireString(snapshot, "displayName", "$snapshot");
            requireTitle(displayName, "$snapshot.displayName");
            ObjectNode host = requireObject(snapshot.get("hostCompatibility"), "$snapshot.hostCompatibility");
            requireExactFields(host, HOST_FIELDS, "$snapshot.hostCompatibility");
            String minimum = requireString(host, "minInclusive", "$snapshot.hostCompatibility");
            String maximum = requireString(host, "maxExclusive", "$snapshot.hostCompatibility");
            require(compareSemVer(semanticVersion(minimum), semanticVersion(maximum)) < 0,
                    "应用快照宿主范围不是有效半开区间");
            List<ParsedReference> references = parseReferences(
                    requireArray(snapshot.get("dashboardRefs"), "$snapshot.dashboardRefs"));
            require(!references.isEmpty() && references.size() <= 5, "应用快照看板引用数量无效");
            UUID entryDashboardId = requireUuid(snapshot, "entryDashboardId", "$snapshot");
            require(references.stream().anyMatch(reference -> reference.dashboardId().equals(entryDashboardId)),
                    "应用快照入口未命中看板引用");
            requireSchemas(snapshot);
            requireOrderedObjects(snapshot, "requiredComponents", COMPONENT_FIELDS, "kind", null, 10);
            requireOrderedObjects(snapshot, "requiredResources", RESOURCE_FIELDS, "resourceId", "digest", 50);
            return new ParsedSnapshot(formatVersion, displayName, minimum, maximum, entryDashboardId, references);
        } catch (JacksonException exception) {
            throw corrupt("应用版本快照不是合法JSON", exception);
        }
    }

    /** 解析1至5个精确看板引用并拒绝稳定ID重复。 */
    private static List<ParsedReference> parseReferences(ArrayNode values) {
        List<ParsedReference> references = new ArrayList<>(values.size());
        Set<UUID> dashboardIds = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "$snapshot.dashboardRefs[" + index + "]";
            ObjectNode value = requireObject(values.get(index), path);
            requireExactFields(value, REFERENCE_FIELDS, path);
            UUID dashboardId = requireUuid(value, "dashboardId", path);
            require(dashboardIds.add(dashboardId), "应用快照稳定看板ID重复");
            String schemaVersion = requireString(value, "schemaVersion", path);
            String digestAlgorithm = requireString(value, "schemaDigestAlgorithm", path);
            String digest = requireString(value, "schemaDigest", path);
            require("tc.dashboard/v1".equals(schemaVersion)
                            && PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM.equals(digestAlgorithm)
                            && SHA256.matcher(digest).matches(),
                    "应用快照看板Schema元数据无效");
            ArrayNode pageNodes = requireArray(value.get("pages"), path + ".pages");
            require(!pageNodes.isEmpty() && pageNodes.size() <= 5, "应用快照页面数量无效");
            List<CurrentApplicationRuntimeProjection.Page> pages = new ArrayList<>(pageNodes.size());
            for (int pageIndex = 0; pageIndex < pageNodes.size(); pageIndex++) {
                String pagePath = path + ".pages[" + pageIndex + "]";
                ObjectNode page = requireObject(pageNodes.get(pageIndex), pagePath);
                requireExactFields(page, PAGE_FIELDS, pagePath);
                pages.add(new CurrentApplicationRuntimeProjection.Page(
                        requireString(page, "id", pagePath), requireString(page, "title", pagePath)));
            }
            references.add(new ParsedReference(
                    dashboardId, requireUuid(value, "dashboardVersionId", path),
                    requirePositiveLong(requireString(value, "dashboardVersionNumber", path)),
                    requireString(value, "title", path), schemaVersion, digestAlgorithm, digest, pages));
        }
        return List.copyOf(references);
    }

    /** 当前合同只允许精确且唯一的tc.dashboard/v1 Schema需求。 */
    private static void requireSchemas(ObjectNode snapshot) {
        ArrayNode schemas = requireArray(snapshot.get("requiredSchemas"), "$snapshot.requiredSchemas");
        require(schemas.size() == 1 && schemas.get(0).isString()
                        && "tc.dashboard/v1".equals(schemas.get(0).asString()),
                "应用快照Schema需求不符合当前合同");
    }

    /** 校验派生对象数组的字段闭集、ASCII严格顺序及可选SHA-256摘要。 */
    private static void requireOrderedObjects(
            ObjectNode snapshot,
            String field,
            Set<String> fields,
            String orderField,
            String digestField,
            int maximumSize) {
        ArrayNode values = requireArray(snapshot.get(field), "$snapshot." + field);
        require(values.size() <= maximumSize, "应用快照派生需求超过上限");
        String previous = null;
        Set<String> componentKinds = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "$snapshot." + field + "[" + index + "]";
            ObjectNode value = requireObject(values.get(index), path);
            requireExactFields(value, fields, path);
            String orderValue = requireString(value, orderField, path);
            require(orderValue.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)
                            && (previous == null || previous.compareTo(orderValue) < 0),
                    "应用快照派生需求未按ASCII严格排序");
            previous = orderValue;
            if (digestField == null) {
                require(componentKinds.add(orderValue), "应用快照组件kind重复");
                requireString(value, "componentVersion", path);
            } else {
                require(SHA256.matcher(requireString(value, digestField, path)).matches(),
                        "应用快照资源摘要无效");
            }
        }
    }

    /** 要求JSON节点是对象。 */
    private static ObjectNode requireObject(JsonNode value, String path) {
        require(value != null && value.isObject(), path + "必须是对象");
        return (ObjectNode) value;
    }

    /** 要求JSON节点是数组。 */
    private static ArrayNode requireArray(JsonNode value, String path) {
        require(value != null && value.isArray(), path + "必须是数组");
        return (ArrayNode) value;
    }

    /** 要求对象精确包含指定非null字段。 */
    private static void requireExactFields(ObjectNode value, Set<String> fields, String path) {
        Set<String> actual = new HashSet<>();
        value.propertyNames().forEach(actual::add);
        require(actual.equals(fields), path + "字段闭集不匹配");
        for (String field : fields) {
            require(value.get(field) != null && !value.get(field).isNull(), path + "." + field + "不得为null");
        }
    }

    /** 要求对象字段保持JSON字符串类型。 */
    private static String requireString(ObjectNode value, String field, String path) {
        JsonNode candidate = value.get(field);
        require(candidate != null && candidate.isString(), path + "." + field + "必须是字符串");
        return candidate.asString();
    }

    /** 要求对象字段是UUID字符串。 */
    private static UUID requireUuid(ObjectNode value, String field, String path) {
        try {
            return UUID.fromString(requireString(value, field, path));
        } catch (IllegalArgumentException exception) {
            throw corrupt(path + "." + field + "不是UUID", exception);
        }
    }

    /** 要求版本号是数据库可表达的规范正Long。 */
    private static long requirePositiveLong(String value) {
        try {
            require(POSITIVE_LONG.matcher(value).matches(), "应用快照看板版本号不是规范正Long");
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw corrupt("应用快照看板版本号溢出", exception);
        }
    }

    /** 公开Title保持原文，但仍拒绝空白、控制符和超过80码点的损坏事实。 */
    private static void requireTitle(String value, String path) {
        int codePoints = value.codePointCount(0, value.length());
        boolean onlyWhitespace = value.codePoints().allMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
        boolean containsControl = value.codePoints().anyMatch(codePoint ->
                codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f);
        require(codePoints >= 1 && codePoints <= 80 && !onlyWhitespace && !containsControl,
                path + "违反Title合同");
    }

    /** 解析当前合同的三段无前导零SemVer且限制每段最多65535。 */
    private static int[] semanticVersion(String value) {
        require(value != null && SEMVER.matcher(value).matches(), "应用快照宿主版本格式无效");
        String[] parts = value.split("\\.");
        try {
            int[] version = {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            for (int part : version) {
                require(part <= 65_535, "应用快照宿主版本段超过65535");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw corrupt("应用快照宿主版本段溢出", exception);
        }
    }

    /** 比较两个三段SemVer。 */
    private static int compareSemVer(int[] left, int[] right) {
        for (int index = 0; index < left.length; index++) {
            int compared = Integer.compare(left[index], right[index]);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /** 将持久不变量失败统一为数据库完整性异常，保留500首因。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw corrupt(message, null);
        }
    }

    /** 建立带可选首因的数据库完整性异常。 */
    private static DataIntegrityViolationException corrupt(String message, Throwable cause) {
        return new DataIntegrityViolationException(message, cause);
    }

    /** 严格快照解析后保留的运行字段。 */
    record ParsedSnapshot(
            String formatVersion,
            String displayName,
            String minimumHostVersionInclusive,
            String maximumHostVersionExclusive,
            UUID entryDashboardId,
            List<ParsedReference> references) {
    }

    /** 快照内一个精确看板引用及其页面摘要。 */
    record ParsedReference(
            UUID dashboardId,
            UUID dashboardVersionId,
            long dashboardVersionNumber,
            String title,
            String schemaVersion,
            String schemaDigestAlgorithm,
            String schemaDigest,
            List<CurrentApplicationRuntimeProjection.Page> pages) {

        /** 转换为域投影时冻结页面列表。 */
        private CurrentApplicationRuntimeProjection.DashboardReference toProjection() {
            return new CurrentApplicationRuntimeProjection.DashboardReference(
                    dashboardId, dashboardVersionId, dashboardVersionNumber, title,
                    schemaVersion, schemaDigestAlgorithm, schemaDigest, pages);
        }
    }

    /** 单行数据库结果；可空目标字段用于区分运行过滤和持久关系损坏。 */
    private record RuntimeRow(
            UUID tenantId,
            UUID projectId,
            UUID applicationId,
            String appKey,
            long publicationRevision,
            UUID applicationVersionId,
            long applicationVersionNumber,
            String snapshot,
            String snapshotDigestAlgorithm,
            String snapshotDigest,
            String calculatedSnapshotDigest,
            Short referencePosition,
            UUID referenceDashboardId,
            UUID referenceDashboardVersionId,
            Long dashboardVersionNumber,
            String schemaVersion,
            String schemaDigestAlgorithm,
            String schemaDigest,
            String calculatedSchemaDigest,
            UUID catalogDashboardId,
            boolean dashboardDeleted,
            UUID dashboardCurrentVersionId) {
    }
}
