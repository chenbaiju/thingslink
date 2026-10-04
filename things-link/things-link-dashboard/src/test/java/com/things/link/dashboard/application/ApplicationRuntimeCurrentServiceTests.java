package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.ApplicationRuntimeCurrentRepository;
import com.things.link.dashboard.domain.CurrentApplicationRuntimeProjection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 当前应用封闭运行描述的事务、身份及精确引用映射单测。 */
class ApplicationRuntimeCurrentServiceTests {

    /** 冻结语法的测试公开定位符。 */
    private static final String APP_KEY = "app_00000000000000000000000000000001";

    /** 当前读取必须加入调用方只读事务，不能单独制造一次不受RLS编排约束的观察。 */
    @Test
    void requiresMandatoryReadOnlyTransaction() throws Exception {
        Method method = ApplicationRuntimeCurrentService.class
                .getMethod("findCurrent", UUID.class, UUID.class, String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
    }

    /** 服务保留精确历史版本引用和页面顺序，不把看板当前版本替换进应用描述。 */
    @Test
    void mapsExactPublishedDashboardReference() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID applicationVersionId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        UUID dashboardVersionId = UUID.randomUUID();
        CurrentApplicationRuntimeProjection projection = projection(
                tenantId, projectId, applicationId, applicationVersionId,
                List.of(new CurrentApplicationRuntimeProjection.DashboardReference(
                        dashboardId, dashboardVersionId, 7, "产线", "tc.dashboard/v1",
                        "PG_JSONB_TEXT_V1_SHA256", "d".repeat(64),
                        List.of(new CurrentApplicationRuntimeProjection.Page("overview", "总览")))));
        ApplicationRuntimeCurrentRepository repository = mock(ApplicationRuntimeCurrentRepository.class);
        when(repository.findCurrent(tenantId, projectId, APP_KEY)).thenReturn(Optional.of(projection));
        ApplicationRuntimeCurrentService service = new ApplicationRuntimeCurrentService(repository);

        CurrentApplicationRuntime current = service.findCurrent(tenantId, projectId, APP_KEY).orElseThrow();

        assertThat(current.applicationId()).isEqualTo(applicationId);
        assertThat(current.applicationVersionId()).isEqualTo(applicationVersionId);
        assertThat(current.dashboards()).singleElement().satisfies(reference -> {
            assertThat(reference.dashboardId()).isEqualTo(dashboardId);
            assertThat(reference.dashboardVersionId()).isEqualTo(dashboardVersionId);
            assertThat(reference.dashboardVersionNumber()).isEqualTo(7);
            assertThat(reference.pages()).containsExactly(
                    new com.things.link.dashboard.application.publication
                            .ApplicationPublishedDashboardReference.Page("overview", "总览"));
        });
    }

    /** 所有看板当前不可运行时仍保留应用和原入口身份，供enduser授权交集统一判定不可用。 */
    @Test
    void preservesEntryIdentityWhenRunnableDashboardSetIsEmpty() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID entryDashboardId = UUID.randomUUID();
        CurrentApplicationRuntimeProjection projection = new CurrentApplicationRuntimeProjection(
                tenantId, projectId, UUID.randomUUID(), APP_KEY, "生产总览", 3,
                UUID.randomUUID(), 2, "tc.application/v1", "1.0.0", "2.0.0",
                entryDashboardId, List.of());
        ApplicationRuntimeCurrentRepository repository = mock(ApplicationRuntimeCurrentRepository.class);
        when(repository.findCurrent(tenantId, projectId, APP_KEY)).thenReturn(Optional.of(projection));

        CurrentApplicationRuntime current = new ApplicationRuntimeCurrentService(repository)
                .findCurrent(tenantId, projectId, APP_KEY).orElseThrow();

        assertThat(current.entryDashboardId()).isEqualTo(entryDashboardId);
        assertThat(current.dashboards()).isEmpty();
    }

    /** 仓储返回不同RLS身份属于内部错误，不能压缩成公开应用不存在。 */
    @Test
    void rejectsRepositoryIdentityDrift() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ApplicationRuntimeCurrentRepository repository = mock(ApplicationRuntimeCurrentRepository.class);
        when(repository.findCurrent(tenantId, projectId, APP_KEY)).thenReturn(Optional.of(projection(
                tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of())));
        ApplicationRuntimeCurrentService service = new ApplicationRuntimeCurrentService(repository);

        assertThatThrownBy(() -> service.findCurrent(tenantId, projectId, APP_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("当前应用运行投影身份发生漂移");
    }

    /** 非规范appKey在任何普通RLS SQL前拒绝，不能查询后伪装为确定不可用。 */
    @Test
    void rejectsNonCanonicalAppKeyBeforeRepositoryAccess() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ApplicationRuntimeCurrentRepository repository = mock(ApplicationRuntimeCurrentRepository.class);
        ApplicationRuntimeCurrentService service = new ApplicationRuntimeCurrentService(repository);

        assertThatThrownBy(() -> service.findCurrent(
                tenantId, projectId, "APP_00000000000000000000000000000001"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findCurrent(
                tenantId, projectId, "APP_00000000000000000000000000000001");
    }

    /** 建立包含冻结应用元数据的持久投影夹具。 */
    private static CurrentApplicationRuntimeProjection projection(
            UUID tenantId,
            UUID projectId,
            UUID applicationId,
            UUID applicationVersionId,
            List<CurrentApplicationRuntimeProjection.DashboardReference> dashboards) {
        return new CurrentApplicationRuntimeProjection(
                tenantId, projectId, applicationId, APP_KEY, "生产总览", 3,
                applicationVersionId, 2, "tc.application/v1", "1.0.0", "2.0.0",
                dashboards.isEmpty() ? UUID.randomUUID() : dashboards.getFirst().dashboardId(), dashboards);
    }
}
