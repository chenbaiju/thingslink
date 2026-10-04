package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S12-2a2a项目运行描述端口的生命周期、身份与失败边界测试。
 *
 * <p>本类只验证application映射；{@code findLiveByIdentity}排除软删的真实SQL合同由既有仓储PG测试负责。</p>
 */
class ProjectRuntimeResolutionServiceTests {

    /** 固定可信租户轴，便于逐项破坏仓储返回身份。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 固定可信项目轴，避免只验证租户匹配。 */
    private final UUID projectId = UUID.randomUUID();
    /** 单测替换项目仓储边界，不伪造数据库RLS或事务证据。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 直接构造只验证映射与前置；事务注解由Spring装配约束。 */
    private final ProjectRuntimeResolutionService service = new ProjectRuntimeResolutionService(repository);

    /** ACTIVE项目只公开归属身份与稳定projectKey。 */
    @Test
    void returnsMinimalDescriptorForActiveProject() {
        when(repository.findLiveByIdentity(tenantId, projectId))
                .thenReturn(Optional.of(project(tenantId, projectId, Project.Status.ACTIVE)));

        assertThat(service.findReadable(tenantId, projectId))
                .contains(new ProjectRuntimeDescriptor(tenantId, projectId, "runtime_project"));
    }

    /** ARCHIVED仍允许只读运行定位，不能套用管理写入口的50017拒绝。 */
    @Test
    void archivedProjectRemainsReadable() {
        when(repository.findLiveByIdentity(tenantId, projectId))
                .thenReturn(Optional.of(project(tenantId, projectId, Project.Status.ARCHIVED)));

        assertThat(service.findReadable(tenantId, projectId)).isPresent();
    }

    /** 删除流程中的全部非读取状态必须统一为空，不向跨域调用方泄露生命周期。 */
    @ParameterizedTest
    @EnumSource(value = Project.Status.class, names = {"DELETING", "PURGING", "PURGED"})
    void deletionLifecycleStatesAreUnavailable(Project.Status status) {
        when(repository.findLiveByIdentity(tenantId, projectId))
                .thenReturn(Optional.of(project(tenantId, projectId, status)));

        assertThat(service.findReadable(tenantId, projectId)).isEmpty();
    }

    /** 软删或不存在由身份限定仓储统一返回空，端口不补查其他公开查询恢复可见性。 */
    @Test
    void missingOrSoftDeletedProjectIsUnavailable() {
        when(repository.findLiveByIdentity(tenantId, projectId)).thenReturn(Optional.empty());

        assertThat(service.findReadable(tenantId, projectId)).isEmpty();
    }

    /** 仓储异常返回错身份事实属于不变量故障，不能被折叠为60023不可见。 */
    @Test
    void mismatchedTenantOrProjectIsInvariantFailure() {
        when(repository.findLiveByIdentity(tenantId, projectId)).thenReturn(
                Optional.of(project(UUID.randomUUID(), projectId, Project.Status.ACTIVE)),
                Optional.of(project(tenantId, UUID.randomUUID(), Project.Status.ACTIVE)));

        assertThatThrownBy(() -> service.findReadable(tenantId, projectId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.findReadable(tenantId, projectId))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 缺失可信二元组须在访问无RLS兜底的项目表前失败。 */
    @Test
    void rejectsMissingTrustedIdentityBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.findReadable(null, projectId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findReadable(tenantId, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    /** 数据库故障保留原异常，不能被上层误映射为确定的60023不可见。 */
    @Test
    void propagatesRepositoryFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("项目运行事实读取失败");
        when(repository.findLiveByIdentity(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> service.findReadable(tenantId, projectId)).isSameAs(failure);
    }

    /** 构造仓储公开投影；软删过滤属于真实SQL而不是Project值对象字段。 */
    private Project project(UUID factTenantId, UUID factProjectId, Project.Status status) {
        return new Project(factProjectId, factTenantId, "运行端口测试", "sh-1", "Asia/Shanghai",
                "runtime_project", status, Instant.parse("2026-09-06T00:00:00Z"));
    }
}
