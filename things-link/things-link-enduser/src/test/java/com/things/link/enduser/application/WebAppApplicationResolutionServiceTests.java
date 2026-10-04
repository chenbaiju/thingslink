package com.things.link.enduser.application;

import com.things.link.dashboard.application.ApplicationRuntimeIdentity;
import com.things.link.dashboard.application.ApplicationRuntimeResolutionService;
import com.things.link.dashboard.application.PublishedApplicationRuntime;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectRuntimeDescriptor;
import com.things.link.project.application.ProjectRuntimeResolutionService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** WebApp应用公开定位跨域编排的直接合同测试。 */
@ExtendWith(MockitoExtension.class)
class WebAppApplicationResolutionServiceTests {

    /** 应用公开稳定键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";

    /** 项目公开稳定键。 */
    private static final String PROJECT_KEY = "project-01";

    /** Dashboard应用运行定位端口替身。 */
    @Mock private ApplicationRuntimeResolutionService applicationResolutionService;

    /** 事务局部二轴范围入口替身。 */
    @Mock private TransactionLocalRlsScope transactionLocalRlsScope;

    /** Project运行可读投影端口替身。 */
    @Mock private ProjectRuntimeResolutionService projectResolutionService;

    /** 被测跨域编排服务。 */
    private WebAppApplicationResolutionService service;

    /** 可信租户标识。 */
    private UUID tenantId;

    /** 可信项目标识。 */
    private UUID projectId;

    /** 可信应用标识。 */
    private UUID applicationId;

    /** 受限函数返回的可信应用身份。 */
    private ApplicationRuntimeIdentity identity;

    /** 每例建立独立身份及编排实例。 */
    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        applicationId = UUID.randomUUID();
        identity = new ApplicationRuntimeIdentity(tenantId, projectId, applicationId);
        service = new WebAppApplicationResolutionService(
                applicationResolutionService, transactionLocalRlsScope, projectResolutionService);
    }

    /** 成功结果只投影公开字段，并保持定位、建范围、应用重验、项目重验的固定顺序。 */
    @Test
    void resolvesPublishedApplicationAfterTrustedScopeAndCrossPortRechecks() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenReturn(Optional.of(
                new PublishedApplicationRuntime(identity, APP_KEY, "工厂总览")));
        when(projectResolutionService.findReadable(tenantId, projectId)).thenReturn(Optional.of(
                new ProjectRuntimeDescriptor(tenantId, projectId, PROJECT_KEY)));

        ResolvedWebAppApplication result = service.resolve(APP_KEY);

        assertThat(result).isEqualTo(new ResolvedWebAppApplication(APP_KEY, "工厂总览", PROJECT_KEY));
        InOrder order = inOrder(applicationResolutionService, transactionLocalRlsScope, projectResolutionService);
        order.verify(applicationResolutionService).locateByAppKey(APP_KEY);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(applicationResolutionService).findCurrent(identity, APP_KEY);
        order.verify(projectResolutionService).findReadable(tenantId, projectId);
    }

    /** 公开编排必须建立只读REQUIRED外层事务，供两个MANDATORY领域端口共享同一连接。 */
    @Test
    void establishesReadOnlyRequiredTransactionBoundary() throws Exception {
        Transactional transaction = WebAppApplicationResolutionService.class
                .getMethod("resolve", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(transaction.readOnly()).isTrue();
    }

    /** 受限函数未定位时统一60023，且不能凭客户端输入建立范围或继续查询。 */
    @Test
    void hidesMissingLocatorBeforeTrustedScope() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.empty());

        assertUnavailable(() -> service.resolve(APP_KEY));

        verifyNoInteractions(transactionLocalRlsScope, projectResolutionService);
        verify(applicationResolutionService, never()).findCurrent(identity, APP_KEY);
    }

    /** 已撤回、软删或并发变化导致当前发布重验为空时统一60023。 */
    @Test
    void hidesMissingCurrentPublicationAfterTrustedScope() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenReturn(Optional.empty());

        assertUnavailable(() -> service.resolve(APP_KEY));

        verify(transactionLocalRlsScope).establish(tenantId, projectId);
        verifyNoInteractions(projectResolutionService);
    }

    /** 当前发布端口返回的任一身份轴漂移都是不变量故障，不能伪装成60023。 */
    @ParameterizedTest
    @EnumSource(IdentityAxis.class)
    void rejectsCurrentPublicationIdentityDriftAsInvariantFailure(IdentityAxis axis) {
        ApplicationRuntimeIdentity drifted = driftedIdentity(axis);
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenReturn(Optional.of(
                new PublishedApplicationRuntime(drifted, APP_KEY, "越界应用")));

        assertThatThrownBy(() -> service.resolve(APP_KEY))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(projectResolutionService);
    }

    /** 当前发布端口即使身份相同也不能把另一个公开键混入成功结果或降格为60023。 */
    @Test
    void rejectsCurrentPublicationAppKeyDriftAsInvariantFailure() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenReturn(Optional.of(
                new PublishedApplicationRuntime(identity, "app_other", "越界应用")));

        assertThatThrownBy(() -> service.resolve(APP_KEY))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(projectResolutionService);
    }

    /** 项目生命周期不可读或项目不存在时与应用缺失使用同一60023。 */
    @Test
    void hidesUnreadableProject() {
        stubPublishedApplication();
        when(projectResolutionService.findReadable(tenantId, projectId)).thenReturn(Optional.empty());

        assertUnavailable(() -> service.resolve(APP_KEY));
    }

    /** 项目端口返回的tenant或project漂移均为服务端不变量故障。 */
    @ParameterizedTest
    @EnumSource(ProjectAxis.class)
    void rejectsProjectDescriptorIdentityDriftAsInvariantFailure(ProjectAxis axis) {
        stubPublishedApplication();
        ProjectRuntimeDescriptor drifted = switch (axis) {
            case TENANT -> new ProjectRuntimeDescriptor(UUID.randomUUID(), projectId, PROJECT_KEY);
            case PROJECT -> new ProjectRuntimeDescriptor(tenantId, UUID.randomUUID(), PROJECT_KEY);
        };
        when(projectResolutionService.findReadable(tenantId, projectId)).thenReturn(Optional.of(drifted));

        assertThatThrownBy(() -> service.resolve(APP_KEY))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 数据库异常必须保持原实例向上传播，不能伪装成资源不可用60023。 */
    @Test
    void preservesInfrastructureFailureFromCurrentPublicationRecheck() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        var failure = new DataAccessResourceFailureException("应用当前发布查询失败");
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenThrow(failure);

        assertThatThrownBy(() -> service.resolve(APP_KEY)).isSameAs(failure);

        verify(transactionLocalRlsScope).establish(tenantId, projectId);
        verifyNoInteractions(projectResolutionService);
    }

    /** 准备通过身份与公开键双重重验的应用事实。 */
    private void stubPublishedApplication() {
        when(applicationResolutionService.locateByAppKey(APP_KEY)).thenReturn(Optional.of(identity));
        when(applicationResolutionService.findCurrent(identity, APP_KEY)).thenReturn(Optional.of(
                new PublishedApplicationRuntime(identity, APP_KEY, "工厂总览")));
    }

    /**
     * 构造只漂移一个应用身份轴的反例。
     *
     * @param axis 待漂移身份轴
     * @return 单轴漂移的身份
     */
    private ApplicationRuntimeIdentity driftedIdentity(IdentityAxis axis) {
        return switch (axis) {
            case TENANT -> new ApplicationRuntimeIdentity(UUID.randomUUID(), projectId, applicationId);
            case PROJECT -> new ApplicationRuntimeIdentity(tenantId, UUID.randomUUID(), applicationId);
            case APPLICATION -> new ApplicationRuntimeIdentity(tenantId, projectId, UUID.randomUUID());
        };
    }

    /** @param invocation 待执行定位 @throws AssertionError 未返回稳定60023时抛出 */
    private static void assertUnavailable(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo(EndUserErrorCode.APPLICATION_RUNTIME_UNAVAILABLE);
                            assertThat(failure.errorCode().code()).isEqualTo(60023);
                            assertThat(failure.errorCode().httpStatus()).isEqualTo(404);
                        });
    }

    /** 应用身份三轴，用于逐轴漂移反例。 */
    private enum IdentityAxis {
        /** 租户轴。 */ TENANT,
        /** 项目轴。 */ PROJECT,
        /** 应用轴。 */ APPLICATION
    }

    /** 项目公开投影的两条身份轴。 */
    private enum ProjectAxis {
        /** 租户轴。 */ TENANT,
        /** 项目轴。 */ PROJECT
    }
}
