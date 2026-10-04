package com.things.link.dashboard.application.publication;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定应用草稿身份、精确看板版本和PostgreSQL摘要的零写入发布候选。
 *
 * <p>候选只证明草稿、看板历史和聚合快照在本次观察中自洽；它不是跨事务资格票据，
 * 不包含应用版本号、发布人、发布时间、发布指针或审计事实。</p>
 *
 * @param applicationId 草稿所属应用ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 应用所属项目ID
 * @param sourceDraftRevision 候选绑定的草稿revision
 * @param snapshot 完整规范ApplicationSnapshot
 * @param postgresqlCanonicalText PostgreSQL返回的snapshot jsonb::text
 * @param snapshotDigestAlgorithm 应用快照摘要算法
 * @param snapshotDigest 应用快照权威摘要
 * @param minimumHostVersionInclusive 草稿宿主范围下界
 * @param maximumHostVersionExclusive 草稿宿主范围上界
 * @param dashboardReferences 后续持久关系的草稿顺序投影
 * @param requiredSchemas ASCII排序去重的Schema版本清单
 * @param requiredComponents ASCII排序去重的组件清单
 * @param requiredResources 按资源ID排序去重的内置资源清单
 */
public record ApplicationPublicationCandidate(
        UUID applicationId,
        UUID tenantId,
        UUID projectId,
        long sourceDraftRevision,
        JsonNode snapshot,
        String postgresqlCanonicalText,
        String snapshotDigestAlgorithm,
        String snapshotDigest,
        String minimumHostVersionInclusive,
        String maximumHostVersionExclusive,
        List<ApplicationVersionDashboardReference> dashboardReferences,
        List<String> requiredSchemas,
        List<DashboardRequiredComponent> requiredComponents,
        List<DashboardRequiredResource> requiredResources) {

    /** 冻结JSON和集合，并拒绝候选身份、摘要或数量越过冻结边界。 */
    public ApplicationPublicationCandidate {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        snapshot = Objects.requireNonNull(snapshot, "snapshot").deepCopy();
        Objects.requireNonNull(postgresqlCanonicalText, "postgresqlCanonicalText");
        Objects.requireNonNull(snapshotDigestAlgorithm, "snapshotDigestAlgorithm");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(minimumHostVersionInclusive, "minimumHostVersionInclusive");
        Objects.requireNonNull(maximumHostVersionExclusive, "maximumHostVersionExclusive");
        dashboardReferences = List.copyOf(dashboardReferences);
        requiredSchemas = List.copyOf(requiredSchemas);
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("sourceDraftRevision不得为负数");
        }
        // 候选可能由测试或后续内部调用直接构造，仍须执行PG规范结果自身的算法、摘要和64KiB边界。
        new PostgreSqlApplicationSnapshotCanonicalForm(
                postgresqlCanonicalText, snapshotDigestAlgorithm, snapshotDigest);
        if (dashboardReferences.isEmpty() || dashboardReferences.size() > 5) {
            throw new IllegalArgumentException("应用候选必须引用1至5个看板版本");
        }
        if (requiredSchemas.isEmpty() || requiredComponents.size() > 10 || requiredResources.size() > 50) {
            throw new IllegalArgumentException("应用候选聚合清单超过冻结边界");
        }
        requireReferenceOrder(dashboardReferences);
        requireStrictAsciiOrder(requiredSchemas, value -> value, "requiredSchemas");
        requireStrictAsciiOrder(requiredComponents,
                value -> value.kind() + "/" + value.componentVersion(), "requiredComponents");
        requireSingleComponentVersion(requiredComponents);
        requireStrictAsciiOrder(requiredResources,
                DashboardRequiredResource::resourceId, "requiredResources");
    }

    /** @return 与内部候选隔离的应用快照副本 */
    @Override
    public JsonNode snapshot() {
        return snapshot.deepCopy();
    }

    /** 要求关系位置覆盖0至n-1且稳定看板ID唯一，避免同一候选产生多种关系解释。 */
    private static void requireReferenceOrder(List<ApplicationVersionDashboardReference> references) {
        Set<UUID> dashboardIds = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            ApplicationVersionDashboardReference reference = references.get(index);
            if (reference.position() != index || !dashboardIds.add(reference.dashboardId())) {
                throw new IllegalArgumentException("dashboardReferences必须位置连续且dashboardId唯一");
            }
        }
    }

    /** 对合同ASCII键执行严格递增检查；相等、倒序或非ASCII输入均拒绝。 */
    private static <T> void requireStrictAsciiOrder(
            List<T> values, java.util.function.Function<T, String> keyFunction, String fieldName) {
        String previous = null;
        for (T value : values) {
            String key = Objects.requireNonNull(keyFunction.apply(value), fieldName + " key");
            if (!key.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)
                    || previous != null && previous.compareTo(key) >= 0) {
                throw new IllegalArgumentException(fieldName + "必须按ASCII严格排序且唯一");
            }
            previous = key;
        }
    }

    /** 单版本宿主按kind只有一个实现，同一候选不能要求同kind的多个组件版本。 */
    private static void requireSingleComponentVersion(List<DashboardRequiredComponent> components) {
        Set<String> kinds = new HashSet<>();
        for (DashboardRequiredComponent component : components) {
            if (!kinds.add(component.kind())) {
                throw new IllegalArgumentException("requiredComponents同一kind只能要求一个版本");
            }
        }
    }
}
