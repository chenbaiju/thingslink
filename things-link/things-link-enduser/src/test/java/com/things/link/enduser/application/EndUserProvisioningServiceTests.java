package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.SubscriptionExpansionGuard;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 终端用户预置用例的单元测试（S11-1a）。 */
class EndUserProvisioningServiceTests {

    private ProjectService projectService;
    private PlanCapacityService capacity;
    private AppUserRepository appUserRepository;
    private PasswordEncoder passwordEncoder;
    /** 以项目持久owner租户建立范围的集中组件替身。 */
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    private EndUserProvisioningService service;

    private UUID projectId;
    private UUID owningTenantId;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        appUserRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tenantTransactionLocalRlsScope = mock(TenantTransactionLocalRlsScope.class);
        capacity = mock(PlanCapacityService.class);
        service = new EndUserProvisioningService(projectService, appUserRepository, passwordEncoder,
                tenantTransactionLocalRlsScope, capacity, mock(ProjectLifecycleAccessService.class),
                mock(SubscriptionExpansionGuard.class));
        projectId = UUID.randomUUID();
        owningTenantId = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(owningTenantId);
        when(capacity.endUsersLimit(owningTenantId, projectId)).thenReturn(3L);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hashed");
    }

    /** 账号落在项目归属租户，用户名被规范化为 trim + 小写，口令经哈希后入库。 */
    @Test
    void provisionCreatesTenantScopedUserWithNormalizedUsername() {
        AppUserReference reference = service.provision(projectId, "  Alice ", "secret123", " 爱丽丝 ");

        assertThat(reference.username()).isEqualTo("alice");
        assertThat(reference.displayName()).isEqualTo("爱丽丝");
        assertThat(reference.tenantId()).isEqualTo(owningTenantId);
        assertThat(reference.status()).isEqualTo(AppUser.Status.ACTIVE);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).create(captor.capture());
        AppUser created = captor.getValue();
        assertThat(created.tenantId()).isEqualTo(owningTenantId);
        assertThat(created.username()).isEqualTo("alice");
        assertThat(created.passwordHash()).isEqualTo("{bcrypt}hashed");
        verify(passwordEncoder).encode("secret123");
        var order = inOrder(projectService, tenantTransactionLocalRlsScope, appUserRepository);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(tenantTransactionLocalRlsScope).establish(owningTenantId);
        order.verify(appUserRepository).create(any());
    }

    /** VIEWER / OPERATOR 无权预置账号。 */
    @Test
    void nonManagerIsRejected() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);

        assertThatThrownBy(() -> service.provision(projectId, "alice", "secret123", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        verifyNoInteractions(tenantTransactionLocalRlsScope, appUserRepository, passwordEncoder);
    }

    /** 口令短于 8 位拒绝，不落库。 */
    @Test
    void shortPasswordIsRejected() {
        assertThatThrownBy(() -> service.provision(projectId, "alice", "short", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
    }

    @Test
    void excessiveUtf8PasswordIsRejectedBeforeHashOrCreate() {
        assertThatThrownBy(() -> service.provision(projectId,"alice","密".repeat(25),null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        verify(passwordEncoder, org.mockito.Mockito.never()).encode(any());
        verify(appUserRepository, org.mockito.Mockito.never()).create(any());
    }

    /** 空白用户名拒绝。 */
    @Test
    void blankUsernameIsRejected() {
        assertThatThrownBy(() -> service.provision(projectId, "   ", "secret123", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
    }

    /** 用户名唯一冲突由 (tenant_id, username) 唯一索引仲裁，映射为 60003。 */
    @Test
    void duplicateUsernameMapsToTaken() {
        doThrow(new DuplicateKeyException("dup")).when(appUserRepository).create(any());

        assertThatThrownBy(() -> service.provision(projectId, "alice", "secret123", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_USERNAME_TAKEN);
    }
}
