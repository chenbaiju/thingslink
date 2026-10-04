package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.ApplicationRuntimeLocation;
import com.things.link.dashboard.domain.ApplicationRuntimeRepository;
import com.things.link.dashboard.domain.PublishedApplicationRuntimeProjection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 应用运行两阶段解析的事务边界、身份复核与异常传播单测。 */
class ApplicationRuntimeResolutionServiceTests {

    /** 冻结语法的测试appKey。 */
    private static final String APP_KEY = "app_00000000000000000000000000000001";

    /** 两个公开阶段都必须加入enduser外层事务，避免首跳与RLS重验落到不同事务边界。 */
    @Test
    void requiresOuterReadOnlyTransactionForBothStages() throws Exception {
        Method locate = ApplicationRuntimeResolutionService.class
                .getMethod("locateByAppKey", String.class);
        Method current = ApplicationRuntimeResolutionService.class
                .getMethod("findCurrent", ApplicationRuntimeIdentity.class, String.class);

        assertMandatoryReadOnly(locate);
        assertMandatoryReadOnly(current);
    }

    /** 首跳只把仓储三元组映射为公开身份，不推导展示或项目业务字段。 */
    @Test
    void locatesOnlyMinimumRuntimeIdentity() {
        ApplicationRuntimeRepository repository = mock(ApplicationRuntimeRepository.class);
        ApplicationRuntimeResolutionService service = new ApplicationRuntimeResolutionService(repository);
        ApplicationRuntimeLocation location = new ApplicationRuntimeLocation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(repository.locateByAppKey(APP_KEY)).thenReturn(Optional.of(location));

        assertThat(service.locateByAppKey(APP_KEY)).contains(new ApplicationRuntimeIdentity(
                location.tenantId(), location.projectId(), location.applicationId()));
    }

    /** 第二阶段使用全部首跳身份和原appKey，并公开当前不可变版本中的displayName。 */
    @Test
    void rechecksCurrentPublicationWithExactIdentityAndAppKey() {
        ApplicationRuntimeRepository repository = mock(ApplicationRuntimeRepository.class);
        ApplicationRuntimeResolutionService service = new ApplicationRuntimeResolutionService(repository);
        ApplicationRuntimeIdentity identity = new ApplicationRuntimeIdentity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PublishedApplicationRuntimeProjection projection = new PublishedApplicationRuntimeProjection(
                identity.tenantId(), identity.projectId(), identity.applicationId(), APP_KEY, "生产总览");
        when(repository.findCurrent(identity.tenantId(), identity.projectId(), identity.applicationId(), APP_KEY))
                .thenReturn(Optional.of(projection));

        assertThat(service.findCurrent(identity, APP_KEY)).contains(
                new PublishedApplicationRuntime(identity, APP_KEY, "生产总览"));
    }

    /** 仓储若返回不同身份或appKey属于内部投影错误，不能降格成Optional.empty。 */
    @Test
    void rejectsRepositoryIdentityDriftAsInvariantFailure() {
        ApplicationRuntimeRepository repository = mock(ApplicationRuntimeRepository.class);
        ApplicationRuntimeResolutionService service = new ApplicationRuntimeResolutionService(repository);
        ApplicationRuntimeIdentity identity = new ApplicationRuntimeIdentity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        PublishedApplicationRuntimeProjection drifted = new PublishedApplicationRuntimeProjection(
                identity.tenantId(), identity.projectId(), UUID.randomUUID(),
                "app_00000000000000000000000000000002", "生产总览");
        when(repository.findCurrent(identity.tenantId(), identity.projectId(), identity.applicationId(), APP_KEY))
                .thenReturn(Optional.of(drifted));

        assertThatThrownBy(() -> service.findCurrent(identity, APP_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("应用运行投影身份发生漂移");
    }

    /** 非规范路径身份在任何SQL前拒绝，避免查询后伪装应用不存在。 */
    @Test
    void rejectsNonCanonicalAppKeyBeforeRepositoryAccess() {
        ApplicationRuntimeRepository repository = mock(ApplicationRuntimeRepository.class);
        ApplicationRuntimeResolutionService service = new ApplicationRuntimeResolutionService(repository);

        assertThatThrownBy(() -> service.locateByAppKey("APP_00000000000000000000000000000001"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).locateByAppKey("APP_00000000000000000000000000000001");
    }

    /** 数据库与持久正文异常保持原异常，60023统一映射只能由enduser编排处理确定空值。 */
    @Test
    void propagatesInfrastructureFailureWithoutDowngrade() {
        ApplicationRuntimeRepository repository = mock(ApplicationRuntimeRepository.class);
        ApplicationRuntimeResolutionService service = new ApplicationRuntimeResolutionService(repository);
        RuntimeException failure = new RuntimeException("database unavailable");
        when(repository.locateByAppKey(APP_KEY)).thenThrow(failure);

        assertThatThrownBy(() -> service.locateByAppKey(APP_KEY)).isSameAs(failure);
    }

    /** 断言公开方法的事务注解保持MANDATORY和只读组合。 */
    private static void assertMandatoryReadOnly(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
    }
}
