package com.things.link.dashboard.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Console公开的完整不可变应用快照。
 *
 * <p>S12-1e2b按发布元数据合同第3.2节逐字段投影持久JSON，避免OpenAPI把Jackson
 * {@code JsonNode}实现方法误生成为业务字段。所有集合及嵌套值均转换为不可变Java值，
 * 响应序列化阶段不再持有可变JSON树引用。</p>
 *
 * @param formatVersion 应用快照合同版本，固定为tc.application/v1
 * @param displayName 应用公开展示名称
 * @param hostCompatibility 受管宿主兼容范围
 * @param dashboardRefs 保留导航顺序的精确看板版本引用
 * @param entryDashboardId 必须命中看板引用的入口看板ID
 * @param requiredSchemas 按ASCII顺序保存的Schema需求
 * @param requiredComponents 按kind与版本排序的组件需求
 * @param requiredResources 按资源ID排序的内置资源需求
 */
@Schema(name = "ApplicationSnapshotResponse", description = "完整tc.application/v1不可变应用快照")
public record ApplicationSnapshotResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "tc.application/v1")
        String formatVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HostCompatibilityResponse hostCompatibility,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DashboardReferenceResponse> dashboardRefs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID entryDashboardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> requiredSchemas,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RequiredComponentResponse> requiredComponents,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RequiredResourceResponse> requiredResources) {

    /** 应用快照根字段闭集。 */
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "formatVersion", "displayName", "hostCompatibility", "dashboardRefs",
            "entryDashboardId", "requiredSchemas", "requiredComponents", "requiredResources");
    /** 宿主兼容范围字段闭集。 */
    private static final Set<String> HOST_COMPATIBILITY_FIELDS = Set.of("minInclusive", "maxExclusive");
    /** 已发布看板引用字段闭集。 */
    private static final Set<String> DASHBOARD_REFERENCE_FIELDS = Set.of(
            "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title",
            "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
    /** 页面导航字段闭集。 */
    private static final Set<String> PAGE_FIELDS = Set.of("id", "title");
    /** 组件需求字段闭集。 */
    private static final Set<String> COMPONENT_FIELDS = Set.of("kind", "componentVersion");
    /** 资源需求字段闭集。 */
    private static final Set<String> RESOURCE_FIELDS = Set.of("resourceId", "digest");
    /** 版本号只接受规范正Long十进制字符串。 */
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");
    /** 持久SHA-256摘要只接受小写十六进制。 */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 冻结顶层集合并拒绝绕过工厂构造空的必填事实。 */
    public ApplicationSnapshotResponse {
        Objects.requireNonNull(formatVersion, "formatVersion");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(hostCompatibility, "hostCompatibility");
        dashboardRefs = List.copyOf(dashboardRefs);
        Objects.requireNonNull(entryDashboardId, "entryDashboardId");
        requiredSchemas = List.copyOf(requiredSchemas);
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
    }

    /**
     * 从数据库封存的完整快照创建严格公开投影。
     *
     * @param source 已由应用版本领域对象防御复制的快照
     * @return 不包含Jackson内部类型的不可变公开快照
     * @throws IllegalStateException 历史快照偏离第3.2节封闭持久合同
     */
    public static ApplicationSnapshotResponse from(JsonNode source) {
        ObjectNode snapshot = requireObject(source, "$snapshot");
        requireExactFields(snapshot, SNAPSHOT_FIELDS, "$snapshot");
        String formatVersion = requireString(snapshot, "formatVersion", "$snapshot");
        if (!"tc.application/v1".equals(formatVersion)) {
            throw corrupt("$snapshot.formatVersion未登记");
        }
        HostCompatibilityResponse hostCompatibility = hostCompatibility(
                requireObject(snapshot.get("hostCompatibility"), "$snapshot.hostCompatibility"));
        List<DashboardReferenceResponse> references = dashboardReferences(
                requireArray(snapshot.get("dashboardRefs"), "$snapshot.dashboardRefs"));
        if (references.isEmpty() || references.size() > 5) {
            throw corrupt("$snapshot.dashboardRefs必须包含1至5项");
        }
        UUID entryDashboardId = requireUuid(snapshot, "entryDashboardId", "$snapshot");
        if (references.stream().noneMatch(reference -> reference.dashboardId().equals(entryDashboardId))) {
            throw corrupt("$snapshot.entryDashboardId未命中看板引用");
        }
        List<String> requiredSchemas = stringArray(
                requireArray(snapshot.get("requiredSchemas"), "$snapshot.requiredSchemas"),
                "$snapshot.requiredSchemas");
        if (!requiredSchemas.equals(List.of("tc.dashboard/v1"))) {
            throw corrupt("$snapshot.requiredSchemas未遵循当前合同");
        }
        List<RequiredComponentResponse> components = requiredComponents(
                requireArray(snapshot.get("requiredComponents"), "$snapshot.requiredComponents"));
        List<RequiredResourceResponse> resources = requiredResources(
                requireArray(snapshot.get("requiredResources"), "$snapshot.requiredResources"));
        if (components.size() > 10 || resources.size() > 50) {
            throw corrupt("$snapshot派生需求超过发布上限");
        }
        return new ApplicationSnapshotResponse(
                formatVersion,
                requireString(snapshot, "displayName", "$snapshot"),
                hostCompatibility,
                references,
                entryDashboardId,
                requiredSchemas,
                components,
                resources);
    }

    /** 把封闭宿主范围转换为不可变公开值。 */
    private static HostCompatibilityResponse hostCompatibility(ObjectNode source) {
        requireExactFields(source, HOST_COMPATIBILITY_FIELDS, "$snapshot.hostCompatibility");
        return new HostCompatibilityResponse(
                requireString(source, "minInclusive", "$snapshot.hostCompatibility"),
                requireString(source, "maxExclusive", "$snapshot.hostCompatibility"));
    }

    /** 把有序看板引用数组转换为不可变公开值。 */
    private static List<DashboardReferenceResponse> dashboardReferences(ArrayNode source) {
        List<DashboardReferenceResponse> values = new ArrayList<>(source.size());
        Set<UUID> dashboardIds = new HashSet<>();
        int index = 0;
        for (JsonNode value : source) {
            String path = "$snapshot.dashboardRefs[" + index + "]";
            ObjectNode reference = requireObject(value, path);
            requireExactFields(reference, DASHBOARD_REFERENCE_FIELDS, path);
            UUID dashboardId = requireUuid(reference, "dashboardId", path);
            if (!dashboardIds.add(dashboardId)) {
                throw corrupt(path + ".dashboardId重复");
            }
            String versionNumber = requireString(reference, "dashboardVersionNumber", path);
            requirePositiveLong(versionNumber, path + ".dashboardVersionNumber");
            String schemaVersion = requireString(reference, "schemaVersion", path);
            String digestAlgorithm = requireString(reference, "schemaDigestAlgorithm", path);
            String digest = requireString(reference, "schemaDigest", path);
            if (!"tc.dashboard/v1".equals(schemaVersion)
                    || !"PG_JSONB_TEXT_V1_SHA256".equals(digestAlgorithm)
                    || !SHA256.matcher(digest).matches()) {
                throw corrupt(path + "的Schema元数据不符合冻结合同");
            }
            List<PageResponse> pages = pages(requireArray(reference.get("pages"), path + ".pages"), path);
            values.add(new DashboardReferenceResponse(
                    dashboardId,
                    requireUuid(reference, "dashboardVersionId", path),
                    versionNumber,
                    requireString(reference, "title", path),
                    schemaVersion,
                    digestAlgorithm,
                    digest,
                    pages));
            index++;
        }
        return List.copyOf(values);
    }

    /** 把页面导航数组转换为不可变公开值。 */
    private static List<PageResponse> pages(ArrayNode source, String referencePath) {
        if (source.isEmpty() || source.size() > 5) {
            throw corrupt(referencePath + ".pages必须包含1至5项");
        }
        List<PageResponse> values = new ArrayList<>(source.size());
        int index = 0;
        for (JsonNode value : source) {
            String path = referencePath + ".pages[" + index + "]";
            ObjectNode page = requireObject(value, path);
            requireExactFields(page, PAGE_FIELDS, path);
            values.add(new PageResponse(requireString(page, "id", path), requireString(page, "title", path)));
            index++;
        }
        return List.copyOf(values);
    }

    /** 把组件需求数组转换为不可变公开值。 */
    private static List<RequiredComponentResponse> requiredComponents(ArrayNode source) {
        List<RequiredComponentResponse> values = new ArrayList<>(source.size());
        int index = 0;
        for (JsonNode value : source) {
            String path = "$snapshot.requiredComponents[" + index + "]";
            ObjectNode component = requireObject(value, path);
            requireExactFields(component, COMPONENT_FIELDS, path);
            values.add(new RequiredComponentResponse(
                    requireString(component, "kind", path),
                    requireString(component, "componentVersion", path)));
            index++;
        }
        return List.copyOf(values);
    }

    /** 把资源需求数组转换为不可变公开值。 */
    private static List<RequiredResourceResponse> requiredResources(ArrayNode source) {
        List<RequiredResourceResponse> values = new ArrayList<>(source.size());
        int index = 0;
        for (JsonNode value : source) {
            String path = "$snapshot.requiredResources[" + index + "]";
            ObjectNode resource = requireObject(value, path);
            requireExactFields(resource, RESOURCE_FIELDS, path);
            String digest = requireString(resource, "digest", path);
            if (!SHA256.matcher(digest).matches()) {
                throw corrupt(path + ".digest不是SHA-256小写十六进制");
            }
            values.add(new RequiredResourceResponse(requireString(resource, "resourceId", path), digest));
            index++;
        }
        return List.copyOf(values);
    }

    /** 把字符串数组转换为不可变列表并拒绝非字符串项。 */
    private static List<String> stringArray(ArrayNode source, String path) {
        List<String> values = new ArrayList<>(source.size());
        int index = 0;
        for (JsonNode value : source) {
            if (!value.isString()) {
                throw corrupt(path + "[" + index + "]必须是字符串");
            }
            values.add(value.asString());
            index++;
        }
        return List.copyOf(values);
    }

    /** 要求JSON值是对象。 */
    private static ObjectNode requireObject(JsonNode value, String path) {
        if (value == null || !value.isObject()) {
            throw corrupt(path + "必须是对象");
        }
        return (ObjectNode) value;
    }

    /** 要求JSON值是数组。 */
    private static ArrayNode requireArray(JsonNode value, String path) {
        if (value == null || !value.isArray()) {
            throw corrupt(path + "必须是数组");
        }
        return (ArrayNode) value;
    }

    /** 要求对象精确包含指定字段且所有字段非null。 */
    private static void requireExactFields(ObjectNode value, Set<String> fields, String path) {
        Set<String> actual = new HashSet<>();
        value.propertyNames().forEach(actual::add);
        if (!actual.equals(fields)) {
            throw corrupt(path + "字段闭集不符合冻结合同");
        }
        for (String field : fields) {
            if (value.get(field).isNull()) {
                throw corrupt(path + "." + field + "不得为null");
            }
        }
    }

    /** 要求对象字段是字符串。 */
    private static String requireString(ObjectNode value, String field, String path) {
        JsonNode candidate = value.get(field);
        if (candidate == null || !candidate.isString()) {
            throw corrupt(path + "." + field + "必须是字符串");
        }
        return candidate.asString();
    }

    /** 要求对象字段是规范UUID字符串。 */
    private static UUID requireUuid(ObjectNode value, String field, String path) {
        String candidate = requireString(value, field, path);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            throw corrupt(path + "." + field + "必须是UUID");
        }
    }

    /** 要求字符串是规范正Long，避免客户端接收领域无法表达的版本号。 */
    private static void requirePositiveLong(String value, String path) {
        try {
            if (!POSITIVE_LONG.matcher(value).matches() || Long.parseLong(value) <= 0) {
                throw corrupt(path + "必须是规范正Long");
            }
        } catch (NumberFormatException exception) {
            throw corrupt(path + "必须是规范正Long");
        }
    }

    /** 将持久历史不一致升级为内部完整性故障，不能降格成普通404。 */
    private static IllegalStateException corrupt(String message) {
        return new IllegalStateException("应用版本快照损坏：" + message);
    }

    /**
     * 受管宿主兼容范围。
     *
     * @param minInclusive 最低兼容宿主SemVer，包含该版本
     * @param maxExclusive 最高兼容宿主SemVer，不包含该版本
     */
    @Schema(name = "ApplicationSnapshotHostCompatibilityResponse")
    public record HostCompatibilityResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String minInclusive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maxExclusive) {
        /** 冻结非空宿主版本边界。 */
        public HostCompatibilityResponse {
            Objects.requireNonNull(minInclusive, "minInclusive");
            Objects.requireNonNull(maxExclusive, "maxExclusive");
        }
    }

    /**
     * 应用快照中的精确看板导航引用。
     *
     * @param dashboardId 稳定看板ID
     * @param dashboardVersionId 精确看板版本ID
     * @param dashboardVersionNumber 看板版本号十进制字符串
     * @param title 应用内导航标题
     * @param schemaVersion 看板Schema合同版本
     * @param schemaDigestAlgorithm 看板Schema摘要算法
     * @param schemaDigest 看板Schema摘要
     * @param pages 保留展示顺序的页面导航
     */
    @Schema(name = "ApplicationSnapshotDashboardReferenceResponse")
    public record DashboardReferenceResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String dashboardVersionNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "tc.dashboard/v1")
            String schemaVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "PG_JSONB_TEXT_V1_SHA256")
            String schemaDigestAlgorithm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String schemaDigest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PageResponse> pages) {
        /** 冻结看板引用及页面集合。 */
        public DashboardReferenceResponse {
            Objects.requireNonNull(dashboardId, "dashboardId");
            Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
            Objects.requireNonNull(dashboardVersionNumber, "dashboardVersionNumber");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
            Objects.requireNonNull(schemaDigest, "schemaDigest");
            pages = List.copyOf(pages);
        }
    }

    /**
     * 看板页面导航摘要。
     *
     * @param id 看板Schema内的稳定页面键
     * @param title 页面展示标题
     */
    @Schema(name = "ApplicationSnapshotPageResponse")
    public record PageResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title) {
        /** 冻结非空页面导航。 */
        public PageResponse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }

    /**
     * 应用快照需要的宿主组件。
     *
     * @param kind 组件kind机器值
     * @param componentVersion 精确组件版本
     */
    @Schema(name = "ApplicationSnapshotRequiredComponentResponse")
    public record RequiredComponentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentVersion) {
        /** 冻结非空组件需求。 */
        public RequiredComponentResponse {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(componentVersion, "componentVersion");
        }
    }

    /**
     * 应用快照需要的宿主内置公开资源。
     *
     * @param resourceId 宿主内置资源标识
     * @param digest 资源字节SHA-256摘要
     */
    @Schema(name = "ApplicationSnapshotRequiredResourceResponse")
    public record RequiredResourceResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String digest) {
        /** 冻结非空资源需求。 */
        public RequiredResourceResponse {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(digest, "digest");
        }
    }
}
