package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.DashboardModelReference;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 绑定持久草稿或不可变历史版本身份与源修订的看板发布候选。
 *
 * <p>该值只证明Schema内部语义、关系投影和PG摘要已完成；外部资格、版本写入、指针推进及审计均未发生。</p>
 *
 * @param dashboardId 草稿所属看板ID
 * @param tenantId 项目所属租户ID
 * @param projectId 草稿所属项目ID
 * @param sourceDraftRevision 候选绑定的草稿revision
 * @param normalizedSchema 注入合同默认值后的完整Schema
 * @param postgresqlCanonicalText 数据库返回的{@code jsonb::text}
 * @param schemaDigestAlgorithm 固定PG摘要算法标识
 * @param schemaDigest 数据库对规范文本UTF-8字节计算的摘要
 * @param modelReferences 与草稿revision绑定的精确模型关系
 * @param requiredComponents 排序去重后的宿主组件清单
 * @param requiredResources 按resourceId排序去重后的内置资源清单
 * @param eligibilityRequirements 保持Schema遍历顺序的完整外部资格需求
 */
public record DashboardPublicationCandidate(
        UUID dashboardId, UUID tenantId, UUID projectId, long sourceDraftRevision,
        JsonNode normalizedSchema, String postgresqlCanonicalText,
        String schemaDigestAlgorithm, String schemaDigest,
        List<DashboardModelReference> modelReferences,
        List<DashboardRequiredComponent> requiredComponents,
        List<DashboardRequiredResource> requiredResources,
        List<DashboardPublicationEligibilityRequirement> eligibilityRequirements) {

    /** 防御复制JSON与集合，并守住候选身份、摘要和持久边界。 */
    public DashboardPublicationCandidate {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        normalizedSchema = Objects.requireNonNull(normalizedSchema, "normalizedSchema").deepCopy();
        Objects.requireNonNull(postgresqlCanonicalText, "postgresqlCanonicalText");
        Objects.requireNonNull(schemaDigestAlgorithm, "schemaDigestAlgorithm");
        Objects.requireNonNull(schemaDigest, "schemaDigest");
        modelReferences = DashboardModelReference.requireExactSchemaProjection(
                normalizedSchema, modelReferences);
        requiredComponents = List.copyOf(requiredComponents);
        requiredResources = List.copyOf(requiredResources);
        eligibilityRequirements = List.copyOf(eligibilityRequirements);
        if (sourceDraftRevision < 0) {
            throw new IllegalArgumentException("发布候选的草稿revision不得为负数");
        }
        // 候选再次核对PG文本边界，避免绕过规范化结果值对象直接构造超限候选。
        if (postgresqlCanonicalText.getBytes(StandardCharsets.UTF_8).length
                > PostgreSqlDashboardSchemaCanonicalForm.MAXIMUM_UTF8_BYTES) {
            throw new IllegalArgumentException("PostgreSQL规范Schema超过512000字节");
        }
        // 复用规范结果值对象同时复核算法和摘要形状，公开构造器也不能制造不可能的候选。
        new PostgreSqlDashboardSchemaCanonicalForm(
                postgresqlCanonicalText, schemaDigestAlgorithm, schemaDigest);
        requireComponentOrder(requiredComponents);
        requireResourceOrder(requiredResources);
        requireDerivedLists(requiredComponents, requiredResources, eligibilityRequirements);
    }

    /** @return 与候选内部状态隔离的规范Schema副本 */
    @Override
    public JsonNode normalizedSchema() {
        return normalizedSchema.deepCopy();
    }

    /** @return 参与摘要的PG规范文本UTF-8字节副本 */
    public byte[] postgresqlCanonicalUtf8() {
        return postgresqlCanonicalText.getBytes(StandardCharsets.UTF_8);
    }

    /** 复核组件清单上限、严格排序、去重以及单kind唯一版本。 */
    private static void requireComponentOrder(List<DashboardRequiredComponent> components) {
        if (components.size() > 10) {
            throw new IllegalArgumentException("看板发布候选最多需要10种组件");
        }
        Comparator<DashboardRequiredComponent> order = Comparator
                .comparing(DashboardRequiredComponent::kind)
                .thenComparing(DashboardRequiredComponent::componentVersion);
        for (int index = 1; index < components.size(); index++) {
            DashboardRequiredComponent previous = components.get(index - 1);
            DashboardRequiredComponent current = components.get(index);
            if (order.compare(previous, current) >= 0) {
                throw new IllegalArgumentException("看板所需组件必须按kind和版本严格升序且不得重复");
            }
            if (previous.kind().equals(current.kind())) {
                throw new IllegalArgumentException("同一组件kind不得声明不同精确版本");
            }
        }
    }

    /** 复核资源清单上限及按resourceId严格排序，从而同时拒绝重复和摘要冲突。 */
    private static void requireResourceOrder(List<DashboardRequiredResource> resources) {
        if (resources.size() > 50) {
            throw new IllegalArgumentException("看板发布候选最多需要50项内置资源");
        }
        for (int index = 1; index < resources.size(); index++) {
            if (resources.get(index - 1).resourceId().compareTo(resources.get(index).resourceId()) >= 0) {
                throw new IllegalArgumentException("看板所需资源必须按resourceId严格升序且不得重复");
            }
        }
    }

    /** 复核公开持久清单确实由同一份完整资格需求派生，防止直接构造伪候选。 */
    private static void requireDerivedLists(
            List<DashboardRequiredComponent> components,
            List<DashboardRequiredResource> resources,
            List<DashboardPublicationEligibilityRequirement> eligibility) {
        Map<String, String> componentVersions = new LinkedHashMap<>();
        Map<String, String> resourceDigests = new LinkedHashMap<>();
        for (DashboardPublicationEligibilityRequirement requirement : eligibility) {
            Objects.requireNonNull(requirement, "eligibilityRequirement");
            if (requirement instanceof DashboardPublicationEligibilityRequirement.HostComponent component) {
                String previous = componentVersions.putIfAbsent(
                        component.kind().name(), component.componentVersion());
                if (previous != null && !previous.equals(component.componentVersion())) {
                    throw new IllegalArgumentException("同一组件kind存在相互冲突的精确版本需求");
                }
            } else if (requirement instanceof DashboardPublicationEligibilityRequirement.BuiltinResource resource) {
                String previous = resourceDigests.putIfAbsent(resource.resourceId(), resource.digest());
                if (previous != null && !previous.equals(resource.digest())) {
                    throw new IllegalArgumentException("同一内置资源标识存在相互冲突的摘要需求");
                }
            }
        }
        List<DashboardRequiredComponent> derivedComponents = componentVersions.entrySet().stream()
                .map(entry -> new DashboardRequiredComponent(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DashboardRequiredComponent::kind)
                        .thenComparing(DashboardRequiredComponent::componentVersion))
                .toList();
        List<DashboardRequiredResource> derivedResources = resourceDigests.entrySet().stream()
                .map(entry -> new DashboardRequiredResource(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DashboardRequiredResource::resourceId))
                .toList();
        if (!components.equals(derivedComponents) || !resources.equals(derivedResources)) {
            throw new IllegalArgumentException("发布候选持久清单必须由完整资格需求精确派生");
        }
    }
}
