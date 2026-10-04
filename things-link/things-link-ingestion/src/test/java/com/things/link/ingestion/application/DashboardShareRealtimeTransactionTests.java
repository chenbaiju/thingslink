package com.things.link.ingestion.application;

import com.things.link.dashboard.application.DashboardSharePlanValidator;
import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRealtimeAccessService;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.enduser.application.AppRealtimeAccessService;
import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadAspect;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 实际Spring双代理及JDBC事务验证：分享分派不能先占CONTROL连接再开启第二层事务。 */
class DashboardShareRealtimeTransactionTests {
    /** 握手与订阅分别计数，既证明顺序也证明事务结束恢复默认路由。 */
    @Test
    void identityAndSubscriptionEachAcquireOneDataConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(source.getConnection()).thenAnswer(call -> {
            // 此观察点发生在事务管理器取得物理连接时；事后在方法体设路由不能通过。
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            return connection;
        });
        DashboardShareRuntimeService runtime = mock(DashboardShareRuntimeService.class);
        when(runtime.read(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            return null;
        });
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ProxyConfiguration.class);
            context.registerBean("transactionManager", DataSourceTransactionManager.class,
                    () -> new DataSourceTransactionManager(source));
            context.registerBean(DatabaseWorkloadAspect.class);
            context.registerBean(DashboardShareRealtimeAccessService.class, () -> new DashboardShareRealtimeAccessService(
                    runtime, new DashboardSharePlanValidator(), mock(DeviceRuntimeDataService.class)));
            context.registerBean(DashboardRealtimeAccessService.class, () -> new DashboardRealtimeAccessService(
                    mock(AppRealtimeAccessService.class), mock(AccountDirectory.class), mock(ProjectService.class),
                    mock(ProjectLifecycleAccessService.class), mock(TransactionLocalRlsScope.class),
                    mock(AppDeviceDataPlaneService.class), context.getBean(DashboardShareRealtimeAccessService.class)));
            context.refresh();
            DashboardRealtimeAccessService service = context.getBean(DashboardRealtimeAccessService.class);
            DashboardRealtimePrincipal principal = principal();
            service.requireIdentity(principal);
            verify(source, times(1)).getConnection();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);

            // 订阅在同次真实事务内遇到撤权，仍只有一个DATA连接并完整退出；不依赖成功业务mock掩盖嵌套。
            org.mockito.Mockito.doAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
                throw new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
            }).when(runtime).read(any());
            assertThatThrownBy(() -> service.requireShareDevices(principal, principal.projectId(),
                    Map.of(UUID.randomUUID(), Set.of("temperature"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
            verify(source, times(2)).getConnection();
            verify(connection, times(2)).close();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        }
    }

    /** 不伪造账号，过期时间足以覆盖本片最低层验证。 */
    private static DashboardRealtimePrincipal principal() {
        return DashboardRealtimePrincipal.share(new DashboardSharePrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now().plusSeconds(60),
                "NONE", "a".repeat(64)));
    }

    /** 采用生产相同的最高优先级路由切面与默认事务拦截器，而非手动调用aspect伪造顺序。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ProxyConfiguration { }
}
