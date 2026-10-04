package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeCurrentService;
import com.things.link.dashboard.application.CurrentApplicationRuntime;
import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 运行访问冻结§3.3及ADR0100：可信App身份、当前精确应用引用与显式READ授权的交集。
 * 不经过公开resolve、不使用Console上下文，也不凭App角色或appKey推导全看板授权。
 */
@Service
public class WebAppApplicationCurrentService {
    /** 公开键语法沿已冻结的服务端随机128bit小写十六进制格式。 */
    private static final Pattern APP_KEY = Pattern.compile("app_[0-9a-f]{32}");
    /** current与Schema共享可信身份与项目生命周期检查，避免授权规则漂移。 */
    private final AppRuntimeIdentityService identity;
    /** Dashboard域一次封闭观察公开当前应用及可运行精确引用。 */
    private final ApplicationRuntimeCurrentService applications;
    /** 只查询当前候选的有界ACTIVE grant集合。 */
    private final AppUserDashboardGrantRepository grants;

    /**
     * 创建App current读取编排，全部持久观察加入同一个只读事务。
     * @param identity 共用App身份门禁
     * @param applications 当前应用公开描述端口
     * @param grants 本域有界授权查询
     */
    public WebAppApplicationCurrentService(AppRuntimeIdentityService identity,
            ApplicationRuntimeCurrentService applications, AppUserDashboardGrantRepository grants) {
        this.identity = identity;
        this.applications = applications;
        this.grants = grants;
    }

    /**
     * 读取当前已授权运行投影；后续Schema与数据请求仍须重新确权，不把本结果当持久授权凭据。
     * @param tenantId 已认证App JWT携带的可信租户
     * @param projectId 已认证App JWT携带的可信项目
     * @param appUserId 已认证App JWT的可信用户subject
     * @param projectGeneration 已认证App JWT的项目生命周期代次
     * @param appKey 请求选择的规范应用公开键，不能反向改变JWT范围
     * @return 当前应用及按原顺序可见的精确看板引用
     */
    @Transactional(readOnly = true)
    public CurrentWebAppApplication current(UUID tenantId, UUID projectId, UUID appUserId,
                                             long projectGeneration, String appKey) {
        AppRuntimeIdentityService.validateParameters(tenantId, projectId, appUserId, projectGeneration);
        if (appKey == null || !APP_KEY.matcher(appKey).matches()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "应用公开键格式不合法");
        }
        identity.requireActive(tenantId, projectId, appUserId, projectGeneration);
        CurrentApplicationRuntime runtime = applications.findCurrent(tenantId, projectId, appKey)
                .orElseThrow(WebAppApplicationCurrentService::unavailable);
        if (!tenantId.equals(runtime.tenantId()) || !projectId.equals(runtime.projectId())
                || !appKey.equals(runtime.appKey())) {
            throw new IllegalStateException("当前应用公开端口返回不一致的可信身份");
        }
        List<UUID> candidates = runtime.dashboards().stream()
                .map(ApplicationPublishedDashboardReference::dashboardId).toList();
        if (candidates.size() > 5 || new HashSet<>(candidates).size() != candidates.size()) {
            throw new IllegalStateException("当前应用公开端口返回重复或越界看板引用");
        }
        if (candidates.isEmpty()) throw unavailable();
        Set<UUID> active = grants.findActiveDashboardIds(tenantId, projectId, appUserId, candidates);
        if (active == null || !new HashSet<>(candidates).containsAll(active)) {
            throw new IllegalStateException("当前应用授权查询返回候选外身份");
        }
        List<ApplicationPublishedDashboardReference> visible = runtime.dashboards().stream()
                .filter(reference -> active.contains(reference.dashboardId())).toList();
        if (visible.isEmpty()) throw unavailable();
        UUID entry = runtime.entryDashboardId() != null && active.contains(runtime.entryDashboardId())
                ? runtime.entryDashboardId() : null;
        return new CurrentWebAppApplication(tenantId, projectId, appUserId, runtime.applicationId(), runtime.appKey(),
                runtime.displayName(), runtime.publicationRevision(), runtime.applicationVersionId(),
                runtime.applicationVersionNumber(), runtime.applicationFormatVersion(),
                runtime.minimumHostVersionInclusive(), runtime.maximumHostVersionExclusive(), entry, visible);
    }

    /** 资源、当前版本、运行状态与grant差异统一隐藏。 */
    private static BusinessException unavailable() {
        return new BusinessException(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
    }

}
