package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectRegion;
import com.things.link.project.domain.ProjectRegionRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 项目创建时区的默认值、IANA 校验与持久化测试。 */
class ProjectServiceTimezoneTests {

    /** 每条测试清理线程上下文，避免同 JVM 的后续用例继承错误账号范围。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 客户端省略时区时采用大陆产品默认值，避免 cron 随部署节点时区漂移。 */
    @Test
    void defaultsTimezoneToAsiaShanghai() {
        Fixture fixture = fixture();

        fixture.service().create("默认时区项目", "sh-1", null);

        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(fixture.projectRepository()).create(project.capture());
        assertThat(project.getValue().timezone()).isEqualTo("Asia/Shanghai");
    }

    /** 合法 IANA 标识按 JDK 规范 ID 保存，而不是把浏览器本地偏移量当作时区。 */
    @Test
    void acceptsIanaTimezone() {
        Fixture fixture = fixture();

        fixture.service().create("乌鲁木齐项目", "sh-1", "  Asia/Urumqi  ");

        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(fixture.projectRepository()).create(project.capture());
        assertThat(project.getValue().timezone()).isEqualTo("Asia/Urumqi");
    }

    /** 未知标识必须在插入项目与 OWNER 绑定前拒绝，不能依赖数据库只检查非空。 */
    @Test
    void rejectsUnknownTimezoneBeforePersistence() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().create("错误时区项目", "sh-1", "China/Unknown"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("项目时区不合法");
    }

    /** @return 已配置可创建上海区域和账号上下文的纯单元测试夹具 */
    private static Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectRegionRepository regionRepository = mock(ProjectRegionRepository.class);
        when(regionRepository.findProjectCreatable("sh-1")).thenReturn(Optional.of(
                new ProjectRegion("sh-1", "上海一", "sh", "上海", true, true, 1)));
        // 本类只验时区语义，提供合法FREE产品投影，不绕过额度守卫。
        EffectiveQuotaPolicyProvider quotaPolicyProvider = mock(EffectiveQuotaPolicyProvider.class);
        when(quotaPolicyProvider.resolveTrustedTenant(tenantId))
                .thenReturn(EffectiveQuotaPolicy.safeDefault(tenantId).withPlanQuota(
                        new com.things.link.project.domain.plan.EffectivePlanQuota(UUID.randomUUID(), 1L,
                                com.things.link.project.domain.plan.ProductRevision1.quotaTemplates().get("FREE"))));
        return new Fixture(new ProjectService(projectRepository, regionRepository,
                mock(ProjectManagementWriteGuard.class), quotaPolicyProvider,
                mock(SubscriptionExpansionGuard.class)), projectRepository);
    }

    /** @param service 被测服务 @param projectRepository 用于捕获写入的仓储替身 */
    private record Fixture(ProjectService service, ProjectRepository projectRepository) {
    }
}
