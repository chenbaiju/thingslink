package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S12-2a1d 项目清理批次范围编排测试；真实事务与清理阶段反例由 Bootstrap 集成测试覆盖。 */
class ProjectCleanupBatchServiceTests {

    /** 围栏确认后必须先建立领取身份的完整范围，才能执行贡献器并提交阶段进度。 */
    @Test
    void establishesClaimScopeBeforeContributorAndProgressCommit() {
        ProjectCleanupAdmissionService admission = mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupRepository repository = mock(ProjectCleanupRepository.class);
        TransactionLocalRlsScope transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        ProjectCleanupContributor contributor = mock(ProjectCleanupContributor.class);
        ProjectCleanupClaim claim = claim();
        ProjectCleanupBatchResult result = ProjectCleanupBatchResult.done();
        when(contributor.stage()).thenReturn(ProjectCleanupStage.TASK);
        when(admission.lockCurrent(claim)).thenReturn(true);
        when(contributor.clean(claim)).thenReturn(result);
        when(repository.completeBatch(claim, ProjectCleanupStage.RULE.name(), 0, null)).thenReturn(true);
        ProjectCleanupBatchService service = new ProjectCleanupBatchService(
                admission, repository, transactionLocalRlsScope, List.of(contributor));

        assertThat(service.execute(claim)).contains(result);

        var order = inOrder(admission, transactionLocalRlsScope, contributor, repository);
        order.verify(admission).lockCurrent(claim);
        order.verify(transactionLocalRlsScope).establish(claim.tenantId(), claim.projectId());
        order.verify(contributor).clean(claim);
        order.verify(repository).completeBatch(claim, ProjectCleanupStage.RULE.name(), 0, null);
    }

    /** 围栏拒绝表示领取已失效，此时不得建立范围或触碰任何领域贡献器。 */
    @Test
    void rejectedClaimDoesNotEstablishScopeOrRunContributor() {
        ProjectCleanupAdmissionService admission = mock(ProjectCleanupAdmissionService.class);
        ProjectCleanupRepository repository = mock(ProjectCleanupRepository.class);
        TransactionLocalRlsScope transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        ProjectCleanupContributor contributor = mock(ProjectCleanupContributor.class);
        ProjectCleanupClaim claim = claim();
        when(contributor.stage()).thenReturn(ProjectCleanupStage.TASK);
        when(admission.lockCurrent(claim)).thenReturn(false);
        ProjectCleanupBatchService service = new ProjectCleanupBatchService(
                admission, repository, transactionLocalRlsScope, List.of(contributor));

        assertThat(service.execute(claim)).isEqualTo(Optional.empty());

        verify(transactionLocalRlsScope, never()).establish(claim.tenantId(), claim.projectId());
        verify(contributor, never()).clean(claim);
        verify(repository, never()).completeBatch(
                claim, ProjectCleanupStage.RULE.name(), 0, null);
    }

    /** @return TASK 阶段的完整持久领取身份 */
    private static ProjectCleanupClaim claim() {
        return new ProjectCleanupClaim(UUID.randomUUID(), UUID.randomUUID(), 3, ProjectCleanupStage.TASK.name(),
                UUID.randomUUID(), Instant.now().plusSeconds(120), false);
    }
}
