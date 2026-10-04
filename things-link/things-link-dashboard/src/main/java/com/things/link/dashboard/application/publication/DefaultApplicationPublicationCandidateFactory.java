package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.draft.ApplicationDraftContractValidator;
import com.things.link.dashboard.application.draft.ApplicationDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedApplicationDraft;
import com.things.link.dashboard.application.schema.DashboardSchemaParseException;
import com.things.link.dashboard.application.schema.DashboardSchemaValidationException;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** 使用持久草稿、精确看板历史和PostgreSQL规范化准备应用发布候选。 */
@Component
public class DefaultApplicationPublicationCandidateFactory implements ApplicationPublicationCandidateFactory {

    /** 构造合同精确ApplicationSnapshot的内部JSON映射器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 重跑应用草稿完整结构合同的原文门面。 */
    private final ApplicationDraftContractValidator draftValidator;
    /** 看板目录、发布状态及精确不可变版本端口。 */
    private final DashboardRepository dashboardRepository;
    /** 重验看板不可变版本Schema、摘要及派生清单的工厂。 */
    private final DashboardPublicationCandidateFactory dashboardCandidateFactory;
    /** 生成应用快照PostgreSQL权威摘要的端口。 */
    private final ApplicationSnapshotCanonicalizer snapshotCanonicalizer;

    /**
     * 创建应用候选工厂。
     *
     * @param draftValidator 应用草稿完整合同门面
     * @param dashboardRepository 看板持久事实端口
     * @param dashboardCandidateFactory 看板历史候选重验工厂
     * @param snapshotCanonicalizer 应用快照PG规范化端口
     */
    public DefaultApplicationPublicationCandidateFactory(
            ApplicationDraftContractValidator draftValidator,
            DashboardRepository dashboardRepository,
            DashboardPublicationCandidateFactory dashboardCandidateFactory,
            ApplicationSnapshotCanonicalizer snapshotCanonicalizer) {
        this.draftValidator = Objects.requireNonNull(draftValidator, "draftValidator");
        this.dashboardRepository = Objects.requireNonNull(dashboardRepository, "dashboardRepository");
        this.dashboardCandidateFactory = Objects.requireNonNull(
                dashboardCandidateFactory, "dashboardCandidateFactory");
        this.snapshotCanonicalizer = Objects.requireNonNull(snapshotCanonicalizer, "snapshotCanonicalizer");
    }

    /** {@inheritDoc} */
    @Override
    public ApplicationPublicationCandidate prepare(ApplicationDraft draft) {
        return prepare(draft, false, false);
    }

    /** {@inheritDoc} */
    @Override
    public ApplicationPublicationCandidate prepareLocked(ApplicationDraft draft) {
        return prepare(draft, true, false);
    }

    /** {@inheritDoc} */
    @Override
    public ApplicationPublicationCandidate prepareHistoricalVersionLocked(ApplicationVersion version) {
        Objects.requireNonNull(version, "version");
        ApplicationDraft historicalDraft = historicalDraft(version);
        ApplicationPublicationCandidate candidate = prepare(historicalDraft, true, true);
        if (!candidate.applicationId().equals(version.applicationId())
                || !candidate.tenantId().equals(version.tenantId())
                || !candidate.projectId().equals(version.projectId())
                || candidate.sourceDraftRevision() != version.sourceDraftRevision()
                || !candidate.snapshot().equals(version.snapshot())
                || !candidate.snapshotDigestAlgorithm().equals(version.snapshotDigestAlgorithm())
                || !candidate.snapshotDigest().equals(version.snapshotDigest())) {
            throw new IllegalStateException("应用历史版本快照或摘要与精确看板重新派生结果不一致");
        }
        return candidate;
    }

    /**
     * 从不可变ApplicationSnapshot只投影草稿原始字段，随后复用完整草稿合同和精确看板派生流程。
     * 任何快照字段缺失或类型漂移都会由草稿校验及最终全快照相等检查拒绝，不能被默认值修复。
     */
    private static ApplicationDraft historicalDraft(ApplicationVersion version) {
        JsonNode snapshot = version.snapshot();
        ObjectNode content = JSON.createObjectNode();
        content.set("formatVersion", snapshot.path("formatVersion").deepCopy());
        content.set("displayName", snapshot.path("displayName").deepCopy());
        content.set("hostCompatibility", snapshot.path("hostCompatibility").deepCopy());
        ArrayNode references = content.putArray("dashboardRefs");
        JsonNode snapshotReferences = snapshot.path("dashboardRefs");
        if (snapshotReferences.isArray()) {
            for (JsonNode reference : snapshotReferences) {
                ObjectNode draftReference = references.addObject();
                draftReference.set("dashboardId", reference.path("dashboardId").deepCopy());
                draftReference.set("dashboardVersionId", reference.path("dashboardVersionId").deepCopy());
                draftReference.set("title", reference.path("title").deepCopy());
            }
        }
        content.set("entryDashboardId", snapshot.path("entryDashboardId").deepCopy());
        try {
            return new ApplicationDraft(
                    version.applicationId(), version.tenantId(), version.projectId(), content,
                    version.sourceDraftRevision(), version.publishedByAccountId(),
                    version.publishedAt(), version.publishedAt());
        } catch (IllegalArgumentException exception) {
            throw failure(ApplicationPublicationQualificationException.Reason.DRAFT_INVALID);
        }
    }

    /** 使用同一解析与派生流程，仅由调用方明确选择普通观察或事务行锁读取。 */
    private ApplicationPublicationCandidate prepare(
            ApplicationDraft draft, boolean lockDashboards, boolean requireHistoricalIntegrity) {
        Objects.requireNonNull(draft, "draft");
        ValidatedApplicationDraft validated = validateDraft(draft);
        JsonNode content = validated.content();
        List<DraftDashboardReference> references = draftReferences(content);
        if (references.isEmpty() || content.path("entryDashboardId").isNull()) {
            throw failure(ApplicationPublicationQualificationException.Reason.EMPTY_APPLICATION);
        }

        // 稳定ID顺序使后续事务片复用同一算法加锁时不会因草稿导航顺序互异形成死锁环。
        List<DraftDashboardReference> identityOrder = references.stream()
                .sorted(Comparator.comparing(reference -> reference.dashboardId().toString()))
                .toList();
        Map<UUID, QualifiedDashboardReference> qualifiedByDashboard = new LinkedHashMap<>();
        for (DraftDashboardReference reference : identityOrder) {
            qualifiedByDashboard.put(reference.dashboardId(),
                    qualifyDashboard(draft, reference, lockDashboards, requireHistoricalIntegrity));
        }

        List<ApplicationPublishedDashboardReference> publishedReferences = new ArrayList<>(references.size());
        List<ApplicationVersionDashboardReference> relationReferences = new ArrayList<>(references.size());
        TreeSet<String> schemas = new TreeSet<>();
        TreeMap<String, String> components = new TreeMap<>();
        TreeMap<String, String> resources = new TreeMap<>();
        for (DraftDashboardReference reference : references) {
            QualifiedDashboardReference qualified = qualifiedByDashboard.get(reference.dashboardId());
            DashboardVersion version = qualified.version();
            DashboardPublicationCandidate dashboardCandidate = qualified.candidate();
            publishedReferences.add(new ApplicationPublishedDashboardReference(
                    reference.dashboardId(), reference.dashboardVersionId(), version.versionNumber(),
                    reference.title(), version.schemaVersion(), version.schemaDigestAlgorithm(),
                    version.schemaDigest(), pages(dashboardCandidate.normalizedSchema())));
            relationReferences.add(new ApplicationVersionDashboardReference(
                    reference.position(), reference.dashboardId(), reference.dashboardVersionId()));
            schemas.add(version.schemaVersion());
            mergeComponents(components, dashboardCandidate.requiredComponents(), requireHistoricalIntegrity);
            mergeResources(resources, dashboardCandidate.requiredResources(), requireHistoricalIntegrity);
        }
        if (components.size() > 10 || resources.size() > 50) {
            if (requireHistoricalIntegrity) {
                throw new IllegalStateException("应用历史版本聚合需求超过已发布冻结边界");
            }
            throw failure(ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
        }

        List<DashboardRequiredComponent> requiredComponents = components.entrySet().stream()
                .map(entry -> new DashboardRequiredComponent(entry.getKey(), entry.getValue()))
                .toList();
        List<DashboardRequiredResource> requiredResources = resources.entrySet().stream()
                .map(entry -> new DashboardRequiredResource(entry.getKey(), entry.getValue()))
                .toList();
        ObjectNode snapshot = snapshot(content, publishedReferences, schemas, requiredComponents, requiredResources);
        PostgreSqlApplicationSnapshotCanonicalForm canonical = snapshotCanonicalizer.canonicalize(snapshot);
        JsonNode hostCompatibility = content.path("hostCompatibility");
        return new ApplicationPublicationCandidate(
                draft.applicationId(), draft.tenantId(), draft.projectId(), draft.revision(), snapshot,
                canonical.text(), canonical.digestAlgorithm(), canonical.digest(),
                hostCompatibility.path("minInclusive").asString(),
                hostCompatibility.path("maxExclusive").asString(), relationReferences,
                List.copyOf(schemas), requiredComponents, requiredResources);
    }

    /** 持久JSONB重新序列化后重跑完整字段语义；原始保存字节限制已在保存入口完成。 */
    private ValidatedApplicationDraft validateDraft(ApplicationDraft draft) {
        try {
            ValidatedApplicationDraft validated = draftValidator.validate(
                    Long.toString(draft.revision()),
                    draft.content().toString().getBytes(StandardCharsets.UTF_8));
            if (validated.expectedRevision() != draft.revision()) {
                throw new IllegalStateException("应用草稿校验结果绑定了错误revision");
            }
            return validated;
        } catch (ApplicationDraftContractViolation exception) {
            throw failure(ApplicationPublicationQualificationException.Reason.DRAFT_INVALID);
        }
    }

    /** 从已验证内容建立保序结构引用，避免后续再次解释不可信字段。 */
    private static List<DraftDashboardReference> draftReferences(JsonNode content) {
        List<DraftDashboardReference> references = new ArrayList<>();
        int position = 0;
        for (JsonNode value : content.path("dashboardRefs")) {
            references.add(new DraftDashboardReference(
                    position++, UUID.fromString(value.path("dashboardId").asString()),
                    UUID.fromString(value.path("dashboardVersionId").asString()),
                    value.path("title").asString()));
        }
        return List.copyOf(references);
    }

    /** 核验看板当前可运行、精确版本归属和不可变Schema封存自洽，不要求目标等于current。 */
    private QualifiedDashboardReference qualifyDashboard(
            ApplicationDraft draft,
            DraftDashboardReference reference,
            boolean lockDashboard,
            boolean requireHistoricalIntegrity) {
        java.util.Optional<DashboardPublicationState> observedState = lockDashboard
                ? dashboardRepository.lockPublicationState(draft.projectId(), reference.dashboardId())
                : dashboardRepository.findPublicationState(draft.projectId(), reference.dashboardId());
        DashboardPublicationState state = observedState.orElseThrow(() -> requireHistoricalIntegrity
                ? new IllegalStateException("应用历史版本引用的看板目录缺失")
                : failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID));
        if (!state.dashboardId().equals(reference.dashboardId())
                || !state.projectId().equals(draft.projectId())
                || !state.tenantId().equals(draft.tenantId())) {
            if (requireHistoricalIntegrity) {
                throw new IllegalStateException("应用历史版本引用的看板目录身份漂移");
            }
            throw failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);
        }
        if (state.deletedAt() != null || state.currentVersionId() == null) {
            throw failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE);
        }
        DashboardVersion version = dashboardRepository.findVersion(
                        draft.projectId(), reference.dashboardId(), reference.dashboardVersionId())
                .orElseThrow(() -> requireHistoricalIntegrity
                        ? new IllegalStateException("应用历史版本的精确看板版本缺失")
                        : failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID));
        if (!version.dashboardId().equals(reference.dashboardId())
                || !version.id().equals(reference.dashboardVersionId())
                || !version.projectId().equals(draft.projectId())
                || !version.tenantId().equals(draft.tenantId())) {
            if (requireHistoricalIntegrity) {
                throw new IllegalStateException("应用历史版本的精确看板版本身份漂移");
            }
            throw failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);
        }
        try {
            DashboardPublicationCandidate candidate = dashboardCandidateFactory.prepareHistoricalVersion(version);
            if (!candidate.dashboardId().equals(version.dashboardId())
                    || !candidate.projectId().equals(version.projectId())
                    || !candidate.tenantId().equals(version.tenantId())) {
                throw new IllegalStateException("看板历史候选身份与精确版本不一致");
            }
            return new QualifiedDashboardReference(version, candidate);
        } catch (DashboardSchemaParseException | DashboardSchemaValidationException exception) {
            throw failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);
        } catch (IllegalStateException exception) {
            if (requireHistoricalIntegrity) {
                throw exception;
            }
            throw failure(ApplicationPublicationQualificationException.Reason.DASHBOARD_REFERENCE_INVALID);
        }
    }

    /** 从完整规范Schema提取1至5个页面的稳定ID和标题，保持原展示顺序。 */
    private static List<ApplicationPublishedDashboardReference.Page> pages(JsonNode schema) {
        List<ApplicationPublishedDashboardReference.Page> pages = new ArrayList<>();
        for (JsonNode page : schema.path("pages")) {
            pages.add(new ApplicationPublishedDashboardReference.Page(
                    page.path("id").asString(), page.path("title").asString()));
        }
        if (pages.isEmpty() || pages.size() > 5) {
            throw new IllegalStateException("已通过完整校验的看板版本页面数量越过冻结边界");
        }
        return List.copyOf(pages);
    }

    /** 聚合同kind组件；同kind需要两个版本时当前单版本宿主无法同时满足，必须拒绝。 */
    private static void mergeComponents(
            Map<String, String> aggregate,
            List<DashboardRequiredComponent> additions,
            boolean requireHistoricalIntegrity) {
        for (DashboardRequiredComponent component : additions) {
            String existing = aggregate.putIfAbsent(component.kind(), component.componentVersion());
            if (existing != null && !existing.equals(component.componentVersion())) {
                if (requireHistoricalIntegrity) {
                    throw new IllegalStateException("应用历史版本包含同组件kind的冲突版本");
                }
                throw failure(ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
            }
        }
    }

    /** 聚合同ID资源；相同公开标识不能在一个应用快照中要求两份不同字节。 */
    private static void mergeResources(
            Map<String, String> aggregate,
            List<DashboardRequiredResource> additions,
            boolean requireHistoricalIntegrity) {
        for (DashboardRequiredResource resource : additions) {
            String existing = aggregate.putIfAbsent(resource.resourceId(), resource.digest());
            if (existing != null && !existing.equals(resource.digest())) {
                if (requireHistoricalIntegrity) {
                    throw new IllegalStateException("应用历史版本包含同资源ID的冲突摘要");
                }
                throw failure(ApplicationPublicationQualificationException.Reason.AGGREGATE_REQUIREMENT_INVALID);
            }
        }
    }

    /** 按S12-0b3a字段闭集构造完整应用快照；JSONB摘要不依赖插入顺序。 */
    private static ObjectNode snapshot(
            JsonNode content,
            List<ApplicationPublishedDashboardReference> references,
            TreeSet<String> schemas,
            List<DashboardRequiredComponent> components,
            List<DashboardRequiredResource> resources) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("formatVersion", "tc.application/v1");
        snapshot.put("displayName", content.path("displayName").asString());
        snapshot.set("hostCompatibility", content.path("hostCompatibility").deepCopy());
        ArrayNode dashboardRefs = snapshot.putArray("dashboardRefs");
        for (ApplicationPublishedDashboardReference reference : references) {
            ObjectNode value = dashboardRefs.addObject();
            value.put("dashboardId", reference.dashboardId().toString());
            value.put("dashboardVersionId", reference.dashboardVersionId().toString());
            value.put("dashboardVersionNumber", Long.toString(reference.dashboardVersionNumber()));
            value.put("title", reference.title());
            value.put("schemaVersion", reference.schemaVersion());
            value.put("schemaDigestAlgorithm", reference.schemaDigestAlgorithm());
            value.put("schemaDigest", reference.schemaDigest());
            ArrayNode pages = value.putArray("pages");
            for (ApplicationPublishedDashboardReference.Page page : reference.pages()) {
                pages.addObject().put("id", page.id()).put("title", page.title());
            }
        }
        snapshot.put("entryDashboardId", content.path("entryDashboardId").asString());
        ArrayNode requiredSchemas = snapshot.putArray("requiredSchemas");
        schemas.forEach(requiredSchemas::add);
        ArrayNode requiredComponents = snapshot.putArray("requiredComponents");
        components.forEach(component -> requiredComponents.addObject()
                .put("kind", component.kind()).put("componentVersion", component.componentVersion()));
        ArrayNode requiredResources = snapshot.putArray("requiredResources");
        resources.forEach(resource -> requiredResources.addObject()
                .put("resourceId", resource.resourceId()).put("digest", resource.digest()));
        return snapshot;
    }

    /** @return 指定稳定原因且不携带被引用事实的资格拒绝 */
    private static ApplicationPublicationQualificationException failure(
            ApplicationPublicationQualificationException.Reason reason) {
        return new ApplicationPublicationQualificationException(reason);
    }

    /** @param position 草稿顺序 @param dashboardId 看板ID @param dashboardVersionId 版本ID @param title 导航标题 */
    private record DraftDashboardReference(
            int position, UUID dashboardId, UUID dashboardVersionId, String title) { }

    /** @param version 精确不可变版本 @param candidate 重验后的看板候选 */
    private record QualifiedDashboardReference(
            DashboardVersion version, DashboardPublicationCandidate candidate) { }
}
