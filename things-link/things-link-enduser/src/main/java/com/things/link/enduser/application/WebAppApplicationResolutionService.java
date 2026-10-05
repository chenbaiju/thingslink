package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeIdentity;
import com.things.link.dashboard.application.ApplicationRuntimeResolutionService;
import com.things.link.dashboard.application.PublishedApplicationRuntime;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectRuntimeDescriptor;
import com.things.link.project.application.ProjectRuntimeResolutionService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * WebApp 登录前应用公开定位的跨域编排服务。
 *
 * <p>S12-2a2a先通过只接受{@code appKey}的受限函数取得可信隔离身份，再在同一只读事务
 * 建立项目RLS范围，并分别重验当前应用发布事实与项目可读事实。定位结果不是用户授权或
 * 看板读取授权，后续运行请求仍须重新确权。</p>
 */
@Service
public class WebAppApplicationResolutionService {

    /** Dashboard公开定位及当前发布重验端口。 */
    private final ApplicationRuntimeResolutionService applicationResolutionService;

    /** 使用受限定位结果建立同连接事务局部二轴范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /** Project公开可读投影端口，Dashboard不能越域读取项目表。 */
    private final ProjectRuntimeResolutionService projectResolutionService;

    /**
     * 创建应用公开定位编排。
     *
     * @param applicationResolutionService Dashboard应用运行定位端口
     * @param transactionLocalRlsScope 事务局部可信租户/项目范围入口
     * @param projectResolutionService Project运行可读投影端口
     */
    public WebAppApplicationResolutionService(
            ApplicationRuntimeResolutionService applicationResolutionService,
            TransactionLocalRlsScope transactionLocalRlsScope,
            ProjectRuntimeResolutionService projectResolutionService) {
        this.applicationResolutionService = Objects.requireNonNull(
                applicationResolutionService, "applicationResolutionService");
        this.transactionLocalRlsScope = Objects.requireNonNull(
                transactionLocalRlsScope, "transactionLocalRlsScope");
        this.projectResolutionService = Objects.requireNonNull(
                projectResolutionService, "projectResolutionService");
    }

    /**
     * 按公开应用键取得登录前最小展示结果。
     *
     * <p>所有预期的缺失或生命周期拒绝都折叠为60023；跨端口身份漂移属于服务端不变量破坏，
     * 数据库、事务及其他基础设施异常同样不在本层捕获，必须保留首因。</p>
     *
     * @param appKey 应用公开稳定键
     * @return 应用及项目最小公开展示结果
     * @throws BusinessException 应用运行入口不可用时返回60023
     * @throws IllegalStateException 跨端口身份事实不一致
     */
    @Transactional(readOnly = true)
    public ResolvedWebAppApplication resolve(String appKey) {
        ApplicationRuntimeIdentity located = applicationResolutionService.locateByAppKey(appKey)
                .orElseThrow(WebAppApplicationResolutionService::unavailable);

        // 受限函数结果是本入口唯一可信二元组；必须先建立范围，再读取普通RLS业务事实。
        transactionLocalRlsScope.establish(located.tenantId(), located.projectId());

        PublishedApplicationRuntime published = applicationResolutionService.findCurrent(located, appKey)
                .orElseThrow(WebAppApplicationResolutionService::unavailable);
        if (!located.equals(published.identity()) || !Objects.equals(appKey, published.appKey())) {
            throw new IllegalStateException("应用运行端口返回了不一致的身份");
        }

        ProjectRuntimeDescriptor project = projectResolutionService
                .findReadable(located.tenantId(), located.projectId())
                .orElseThrow(WebAppApplicationResolutionService::unavailable);
        if (!located.tenantId().equals(project.tenantId())
                || !located.projectId().equals(project.projectId())) {
            throw new IllegalStateException("项目运行端口返回了不一致的身份");
        }

        return new ResolvedWebAppApplication(published.appKey(), published.displayName(), project.projectKey());
    }

    /** @return 不泄露应用、版本、项目或跨端口失配原因的统一运行错误 */
    private static BusinessException unavailable() {
        return new BusinessException(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
    }
}
