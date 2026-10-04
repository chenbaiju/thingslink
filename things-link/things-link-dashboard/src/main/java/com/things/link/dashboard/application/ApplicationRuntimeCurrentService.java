package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.dashboard.domain.ApplicationRuntimeCurrentRepository;
import com.things.link.dashboard.domain.CurrentApplicationRuntimeProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 向enduser公开当前应用的封闭运行描述。
 *
 * <p>调用方先以可信身份建立项目RLS，本服务再加入同一只读事务完成一次数据库观察。确定不可运行
 * 仅返回空值；仓储摘要、关系或快照异常保持首因传播，不能伪装为60023。</p>
 */
@Service
public class ApplicationRuntimeCurrentService {

    /** ADR0096冻结的公开定位符语法。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");

    /** 当前应用封闭投影的普通RLS端口。 */
    private final ApplicationRuntimeCurrentRepository repository;

    /**
     * 创建当前应用运行服务。
     *
     * @param repository 单次观察持久端口
     */
    public ApplicationRuntimeCurrentService(ApplicationRuntimeCurrentRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 在调用方已建立的可信项目范围内读取当前应用描述。
     *
     * @param tenantId 可信项目租户ID
     * @param projectId 可信项目ID
     * @param appKey 规范公开定位符
     * @return 应用未发布、已撤回、软删或不存在时为空
     * @throws IllegalArgumentException 身份缺失或appKey不规范
     * @throws IllegalStateException 缺少调用方外层事务或仓储返回身份漂移
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<CurrentApplicationRuntime> findCurrent(UUID tenantId, UUID projectId, String appKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        if (appKey == null || !APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("appKey必须是app_加32位小写十六进制");
        }
        return repository.findCurrent(tenantId, projectId, appKey)
                .map(projection -> mapProjection(tenantId, projectId, appKey, projection));
    }

    /** 仓储投影必须保持查询身份，映射阶段只转换跨域公开值。 */
    private static CurrentApplicationRuntime mapProjection(
            UUID tenantId,
            UUID projectId,
            String appKey,
            CurrentApplicationRuntimeProjection projection) {
        if (!tenantId.equals(projection.tenantId())
                || !projectId.equals(projection.projectId())
                || !appKey.equals(projection.appKey())) {
            throw new IllegalStateException("当前应用运行投影身份发生漂移");
        }
        List<ApplicationPublishedDashboardReference> dashboards = projection.dashboards().stream()
                .map(ApplicationRuntimeCurrentService::mapDashboard)
                .toList();
        return new CurrentApplicationRuntime(
                projection.tenantId(), projection.projectId(), projection.applicationId(), projection.appKey(),
                projection.displayName(), projection.publicationRevision(), projection.applicationVersionId(),
                projection.applicationVersionNumber(), projection.applicationFormatVersion(),
                projection.minimumHostVersionInclusive(), projection.maximumHostVersionExclusive(),
                projection.entryDashboardId(), dashboards);
    }

    /** 将域内持久投影转换为现有应用发布精确引用，复用其格式和摘要防御检查。 */
    private static ApplicationPublishedDashboardReference mapDashboard(
            CurrentApplicationRuntimeProjection.DashboardReference reference) {
        return new ApplicationPublishedDashboardReference(
                reference.dashboardId(), reference.dashboardVersionId(), reference.dashboardVersionNumber(),
                reference.title(), reference.schemaVersion(), reference.schemaDigestAlgorithm(),
                reference.schemaDigest(), reference.pages().stream()
                .map(page -> new ApplicationPublishedDashboardReference.Page(page.id(), page.title()))
                .toList());
    }
}
