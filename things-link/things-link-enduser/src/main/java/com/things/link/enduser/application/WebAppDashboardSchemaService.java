package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeSchemaService;
import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 运行访问冻结§3.4、发布元数据合同§5.3：重新确权后只读取一个精确Dashboard Schema。
 * 当前应用版本与发布代次由Dashboard窄端口封闭复核，结果不授予后续数据读取权。
 */
@Service
public class WebAppDashboardSchemaService {
    /** 公开键只作已认证范围内的选择器，禁止反向解析租户或项目。 */
    private static final Pattern APP_KEY = Pattern.compile("app_[0-9a-f]{32}");
    /** 与current完全一致的App身份、角色和项目生命周期规则。 */
    private final AppRuntimeIdentityService identity;
    /** 仅返回目标精确版本，不能借current加载所有引用或其他Schema。 */
    private final ApplicationRuntimeSchemaService schemas;
    /** 本域只查询该目标Dashboard的ACTIVE READ grant。 */
    private final AppUserDashboardGrantRepository grants;

    /** 创建Schema编排；Dashboard端口负责元数据与持久摘要完整性。 */
    public WebAppDashboardSchemaService(AppRuntimeIdentityService identity,
            ApplicationRuntimeSchemaService schemas, AppUserDashboardGrantRepository grants) {
        this.identity = identity;
        this.schemas = schemas;
        this.grants = grants;
    }

    /**
     * 每次请求重验App身份、精确入口和单目标授权；数据库失败保留原首因。
     * @param tenantId 已认证App租户
     * @param projectId 已认证App项目
     * @param appUserId 已认证App用户subject
     * @param projectGeneration 已认证App项目生命周期代次
     * @param appKey 规范应用公开键
     * @param applicationVersionId 预期当前应用精确版本
     * @param expectedPublicationRevision 预期当前应用正发布代次
     * @param dashboardVersionId 该应用版本内的目标看板精确版本
     * @return 单看板完整Schema及精确元数据，HTTP包装另由API层投影
     */
    @Transactional(readOnly = true)
    public RuntimeDashboardSchema schema(UUID tenantId, UUID projectId, UUID appUserId,
            long projectGeneration, String appKey, UUID applicationVersionId,
            long expectedPublicationRevision, UUID dashboardVersionId) {
        AppRuntimeIdentityService.validateParameters(tenantId, projectId, appUserId, projectGeneration);
        if (appKey == null || !APP_KEY.matcher(appKey).matches() || applicationVersionId == null
                || dashboardVersionId == null || expectedPublicationRevision <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "看板Schema精确上下文格式不合法");
        }
        identity.requireActive(tenantId, projectId, appUserId, projectGeneration);
        RuntimeDashboardSchema result = schemas.findSchema(tenantId, projectId, appKey,
                applicationVersionId, expectedPublicationRevision, dashboardVersionId)
                .orElseThrow(WebAppDashboardSchemaService::unavailable);
        // 端口漂移是真实内部错误；不能拿错域或错版本的结果继续查询本域grant。
        if (!tenantId.equals(result.tenantId()) || !projectId.equals(result.projectId())
                || !appKey.equals(result.appKey()) || !applicationVersionId.equals(result.applicationVersionId())
                || expectedPublicationRevision != result.publicationRevision()
                || !dashboardVersionId.equals(result.dashboardVersionId())
                || result.applicationId() == null || result.dashboardId() == null) {
            throw new IllegalStateException("Schema公开端口返回不一致的精确身份");
        }
        Set<UUID> active = grants.findActiveDashboardIds(tenantId, projectId, appUserId, List.of(result.dashboardId()));
        if (active == null || active.stream().anyMatch(id -> !result.dashboardId().equals(id))) {
            throw new IllegalStateException("Schema授权查询返回目标外身份");
        }
        if (active.isEmpty()) throw unavailable();
        return result;
    }

    /** 版本、发布代次、目录、精确关系或grant差异统一隐藏为60023。 */
    private static BusinessException unavailable() {
        return new BusinessException(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
    }
}
