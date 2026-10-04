package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import com.things.link.dashboard.application.publication.DashboardSchemaCanonicalizer;
import com.things.link.dashboard.application.publication.PostgreSqlDashboardSchemaCanonicalForm;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** 使用完整内部Schema管线和PostgreSQL规范化端口准备发布候选。 */
@Component
public class DefaultDashboardPublicationCandidateFactory implements DashboardPublicationCandidateFactory {

    /** 把派生清单转换为持久JSON数组，用于复核不可变版本没有形成第二份解释。 */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    /** 严格Schema验证器。 */
    private final DashboardSchemaValidator schemaValidator;
    /** 数据库权威规范化端口。 */
    private final DashboardSchemaCanonicalizer canonicalizer;

    /**
     * 创建候选工厂。
     *
     * @param parser 严格原始字节解析器
     * @param canonicalizer PostgreSQL规范化与摘要端口
     */
    public DefaultDashboardPublicationCandidateFactory(
            DashboardSchemaParser parser, DashboardSchemaCanonicalizer canonicalizer) {
        this.schemaValidator = DashboardSchemaValidator.using(Objects.requireNonNull(parser, "parser"));
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    /** {@inheritDoc} */
    @Override
    public DashboardPublicationCandidate prepare(DashboardDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return prepare(draft.dashboardId(), draft.tenantId(), draft.projectId(), draft.revision(),
                draft.content(), draft.modelReferences());
    }

    /** {@inheritDoc} */
    @Override
    public DashboardPublicationCandidate prepareHistoricalVersion(DashboardVersion version) {
        Objects.requireNonNull(version, "version");
        DashboardPublicationCandidate candidate = prepare(
                version.dashboardId(), version.tenantId(), version.projectId(), version.sourceDraftRevision(),
                version.schema(), version.modelReferences());
        // 回滚必须消费当年真正封存的同一版本，任何历史元数据漂移都按当前资格无效处理。
        if (!DashboardDraft.SCHEMA_VERSION.equals(version.schemaVersion())
                || !version.schema().equals(candidate.normalizedSchema())
                || !version.schemaDigestAlgorithm().equals(candidate.schemaDigestAlgorithm())
                || !version.schemaDigest().equals(candidate.schemaDigest())
                || !version.requiredComponents().equals(JSON_MAPPER.valueToTree(candidate.requiredComponents()))
                || !version.requiredResources().equals(JSON_MAPPER.valueToTree(candidate.requiredResources()))) {
            throw new IllegalStateException("不可变看板版本的Schema、摘要或派生清单不一致");
        }
        return candidate;
    }

    /** 从草稿或不可变版本的同一组封存事实重跑完整内部Schema管线。 */
    private DashboardPublicationCandidate prepare(
            UUID dashboardId,
            UUID tenantId,
            UUID projectId,
            long sourceDraftRevision,
            JsonNode content,
            List<DashboardModelReference> references) {
        // 草稿和版本都来自JSONB，但状态变化仍须重跑结构、默认、引用与组合语义，不能沿用旧结论。
        DashboardSchemaValidationResult validation = schemaValidator.validateAndNormalize(
                content.toString().getBytes(StandardCharsets.UTF_8));
        if (validation.scope() != DashboardSchemaValidationResult.Scope.COMPLETE_INTERNAL_SCHEMA_SEMANTICS) {
            throw new IllegalStateException("看板Schema尚未完成全部内部语义校验");
        }
        JsonNode normalizedSchema = validation.normalizedRoot();
        List<DashboardModelReference> modelReferences = DashboardModelReference.requireExactSchemaProjection(
                normalizedSchema, references);
        PostgreSqlDashboardSchemaCanonicalForm canonical = canonicalizer.canonicalize(normalizedSchema);
        CandidateProjection projection = project(validation.unresolvedRequirements());
        return new DashboardPublicationCandidate(
                dashboardId, tenantId, projectId, sourceDraftRevision,
                normalizedSchema, canonical.text(), canonical.digestAlgorithm(), canonical.digest(),
                modelReferences, projection.requiredComponents(), projection.requiredResources(),
                projection.eligibilityRequirements());
    }

    /** 将包内需求投影为公开不可变API，并同步派生持久化清单。 */
    private static CandidateProjection project(List<DashboardSchemaExternalRequirement> requirements) {
        TreeSet<DashboardRequiredComponent> components = new TreeSet<>(Comparator
                .comparing(DashboardRequiredComponent::kind)
                .thenComparing(DashboardRequiredComponent::componentVersion));
        Map<String, String> resources = new LinkedHashMap<>();
        List<DashboardPublicationEligibilityRequirement> eligibility = new ArrayList<>(requirements.size());
        for (DashboardSchemaExternalRequirement requirement : requirements) {
            if (requirement instanceof HostComponentRequirement value) {
                DashboardRequiredComponent component = new DashboardRequiredComponent(
                        value.kind().name(), value.componentVersion());
                components.add(component);
                eligibility.add(new DashboardPublicationEligibilityRequirement.HostComponent(
                        enumValue(DashboardPublicationEligibilityRequirement.ComponentKind.class, value.kind()),
                        value.componentVersion(), value.hostCompatibility().minInclusive(),
                        value.hostCompatibility().maxExclusive()));
            } else if (requirement instanceof BuiltinResourceRequirement value) {
                String existingDigest = resources.putIfAbsent(value.resourceId(), value.digest());
                if (existingDigest != null && !existingDigest.equals(value.digest())) {
                    throw new DashboardSchemaValidationException(
                            DashboardSchemaValidationException.Reason.INVALID_REFERENCE,
                            "$.pages[].components[].props.resourceId",
                            "同一内置资源标识声明了不同摘要");
                }
                eligibility.add(new DashboardPublicationEligibilityRequirement.BuiltinResource(
                        value.resourceId(), value.digest()));
            } else if (requirement instanceof ModelReferenceRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.ModelReference(
                        value.modelKey(), UUID.fromString(value.versionId()), value.digestAlgorithm(),
                        value.digest(), value.profile()));
            } else if (requirement instanceof DefaultDeviceRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.DefaultDevice(
                        value.variableKey(), enumValue(DashboardPublicationEligibilityRequirement.VariableType.class,
                                value.variableType()), value.modelKey(), UUID.fromString(value.deviceId())));
            } else if (requirement instanceof ModelPropertyRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.ModelProperty(
                        value.modelKey(), value.propertyKey(), value.allowedDataTypes().stream()
                                .map(type -> enumValue(
                                        DashboardPublicationEligibilityRequirement.PropertyDataType.class, type))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())));
            } else if (requirement instanceof HistoricalPropertyRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.HistoricalProperty(
                        value.variableKey(), value.modelKey(), value.propertyKey(), value.timeRangeVariableKey(),
                        DashboardPublicationEligibilityRequirement.HistoryGranularity.valueOf(value.granularity()),
                        DashboardPublicationEligibilityRequirement.HistoryAggregation.valueOf(value.aggregation())));
            } else if (requirement instanceof ModelGaugeRangeRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.ModelGaugeRange(
                        value.modelKey(), value.propertyKey()));
            } else if (requirement instanceof DataAdapterRequirement value) {
                eligibility.add(new DashboardPublicationEligibilityRequirement.DataAdapter(
                        enumValue(DashboardPublicationEligibilityRequirement.AdapterCapability.class,
                                value.capability()),
                        enumValue(DashboardPublicationEligibilityRequirement.BindingSource.class, value.source()),
                        value.variableKey(),
                        enumValue(DashboardPublicationEligibilityRequirement.VariableType.class,
                                value.variableType()),
                        value.modelKey(), value.propertyKey()));
            } else {
                throw new IllegalStateException("存在未投影的看板外部资格需求类型");
            }
        }
        List<DashboardRequiredResource> sortedResources = resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DashboardRequiredResource(entry.getKey(), entry.getValue()))
                .toList();
        if (components.size() > 10 || sortedResources.size() > 50) {
            throw new IllegalStateException("看板发布派生清单超过持久化合同上限");
        }
        return new CandidateProjection(List.copyOf(components), sortedResources, List.copyOf(eligibility));
    }

    /** 按同名机器值把内部闭集映射到公开闭集，新增内部值未投影时立即失败。 */
    private static <T extends Enum<T>> T enumValue(Class<T> target, Enum<?> source) {
        return Enum.valueOf(target, source.name());
    }

    /** @param requiredComponents 组件清单 @param requiredResources 资源清单 @param eligibilityRequirements 完整外部需求 */
    private record CandidateProjection(
            List<DashboardRequiredComponent> requiredComponents,
            List<DashboardRequiredResource> requiredResources,
            List<DashboardPublicationEligibilityRequirement> eligibilityRequirements) { }
}
