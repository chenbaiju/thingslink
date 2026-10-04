package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.domain.ApplicationRuntimeSchemaRepository;
import com.things.link.dashboard.domain.CurrentApplicationRuntimeProjection;
import com.things.link.dashboard.domain.RuntimeDashboardSchemaProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 向enduser公开单个精确Dashboard Schema包的窄服务。
 *
 * <p>调用方须先建立可信项目RLS；本服务加入同一只读事务并重跑目标历史Schema的完整内部语义，
 * 但不查询D-145宿主资格或其他看板Schema。确定上下文、引用或运行状态不匹配返回空，持久损坏保持500。</p>
 */
@Service
public class ApplicationRuntimeSchemaService {

    /** ADR0096冻结的应用公开键语法。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");

    /** 单次普通RLS持久观察。 */
    private final ApplicationRuntimeSchemaRepository repository;
    /** 历史Schema完整内部语义与派生清单复核器，不执行Host资格。 */
    private final DashboardPublicationCandidateFactory candidateFactory;

    /**
     * 创建运行Schema服务。
     *
     * @param repository 精确Schema持久端口
     * @param candidateFactory 看板历史版本内部语义复核器
     */
    public ApplicationRuntimeSchemaService(
            ApplicationRuntimeSchemaRepository repository,
            DashboardPublicationCandidateFactory candidateFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory");
    }

    /**
     * 读取与请求当前应用上下文完全一致的单个Dashboard Schema。
     *
     * @param tenantId 可信App身份租户ID
     * @param projectId 可信App身份项目ID
     * @param appKey 规范应用公开键
     * @param applicationVersionId 请求绑定的当前应用版本ID
     * @param expectedPublicationRevision 请求绑定的当前发布代次
     * @param dashboardVersionId 应用引用的精确看板版本ID
     * @return 当前上下文、引用或看板运行状态不满足时为空
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<RuntimeDashboardSchema> findSchema(
            UUID tenantId,
            UUID projectId,
            String appKey,
            UUID applicationVersionId,
            long expectedPublicationRevision,
            UUID dashboardVersionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        if (appKey == null || !APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("appKey必须是app_加32位小写十六进制");
        }
        if (expectedPublicationRevision <= 0) {
            throw new IllegalArgumentException("expectedPublicationRevision必须为正数");
        }
        return repository.findSchema(tenantId, projectId, appKey, applicationVersionId,
                        expectedPublicationRevision, dashboardVersionId)
                .flatMap(projection -> toRuntimeSchema(
                        tenantId, projectId, appKey, applicationVersionId,
                        expectedPublicationRevision, dashboardVersionId, projection));
    }

    /** 严格复核查询身份和历史Schema后，才按目录运行状态决定是否公开。 */
    private Optional<RuntimeDashboardSchema> toRuntimeSchema(
            UUID tenantId,
            UUID projectId,
            String appKey,
            UUID applicationVersionId,
            long expectedPublicationRevision,
            UUID dashboardVersionId,
            RuntimeDashboardSchemaProjection projection) {
        if (!tenantId.equals(projection.tenantId())
                || !projectId.equals(projection.projectId())
                || !appKey.equals(projection.appKey())
                || !applicationVersionId.equals(projection.applicationVersionId())
                || expectedPublicationRevision != projection.publicationRevision()
                || !dashboardVersionId.equals(projection.dashboardVersion().id())) {
            throw new IllegalStateException("运行Schema投影身份发生漂移");
        }
        final DashboardPublicationCandidate candidate;
        try {
            candidate = candidateFactory.prepareHistoricalVersion(projection.dashboardVersion());
        } catch (RuntimeException exception) {
            // 历史版本已是持久事实；重新解析失败表示数据库完整性损坏，不能沿草稿业务错误码返回400。
            throw new IllegalStateException("运行Dashboard Schema内部语义或派生清单损坏", exception);
        }
        if (!projection.dashboardVersion().dashboardId().equals(candidate.dashboardId())
                || !projection.tenantId().equals(candidate.tenantId())
                || !projection.projectId().equals(candidate.projectId())
                || projection.dashboardVersion().sourceDraftRevision() != candidate.sourceDraftRevision()) {
            throw new IllegalStateException("运行Dashboard Schema复核候选身份发生漂移");
        }
        requireExactNavigation(projection.navigationPages(), candidate.normalizedSchema());
        if (!projection.dashboardRunnable()) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeDashboardSchema(
                projection.tenantId(), projection.projectId(), projection.applicationId(), projection.appKey(),
                projection.applicationVersionId(), projection.publicationRevision(), candidate.dashboardId(),
                projection.dashboardVersion().id(), projection.dashboardVersion().versionNumber(),
                projection.dashboardVersion().schemaVersion(), candidate.schemaDigestAlgorithm(), candidate.schemaDigest(),
                candidate.requiredComponents(), candidate.requiredResources(), candidate.normalizedSchema(),
                projection.schemaUtf8Bytes()));
    }

    /** 应用快照封存的页面导航必须逐序等于目标规范Schema，不能只比较版本ID和摘要。 */
    private static void requireExactNavigation(
            java.util.List<CurrentApplicationRuntimeProjection.Page> expected, JsonNode schema) {
        JsonNode pages = schema.path("pages");
        if (!pages.isArray() || pages.size() != expected.size()) {
            throw new IllegalStateException("应用快照页面导航与Dashboard Schema不一致");
        }
        for (int index = 0; index < expected.size(); index++) {
            JsonNode page = pages.get(index);
            CurrentApplicationRuntimeProjection.Page snapshotPage = expected.get(index);
            if (!page.isObject()
                    || !page.path("id").isString()
                    || !page.path("title").isString()
                    || !snapshotPage.id().equals(page.path("id").asString())
                    || !snapshotPage.title().equals(page.path("title").asString())) {
                throw new IllegalStateException("应用快照页面导航与Dashboard Schema不一致");
            }
        }
    }
}
