package com.things.link.telemetry.application;

import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.telemetry.domain.OverviewTrendRepository;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverviewTrendsServiceTests {
    final UUID project = UUID.randomUUID(), ownerTenant = UUID.randomUUID();
    final OverviewTrendRepository repository = mock(OverviewTrendRepository.class);
    final ProjectService projects = mock(ProjectService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:23:45Z"), ZoneOffset.UTC);
    final OverviewTrendsService service = new OverviewTrendsService(repository, projects, clock);
    final Instant to = Instant.parse("2026-10-08T10:00:00Z");

    void member() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(project)).thenReturn(ownerTenant);
    }
    @Test void returnsAllChartsAndNullForMissingOrPartialBucketsAndPreservesExplicitZero() {
        member();
        var from = to.minusSeconds(3 * 86400);
        when(repository.source(ownerTenant, project, from, to)).thenReturn("SIMULATED");
        when(repository.aggregate(ownerTenant, project, from, to, 3, "SIMULATED"))
                .thenReturn(List.of(new OverviewTrendRepository.Bucket("message.total", 0, 0, 3),
                        new OverviewTrendRepository.Bucket("message.total", 1, 18, 2),
                        new OverviewTrendRepository.Bucket("device.active", 0, 5, 3)));
        var result = service.get(project, 3);
        assertThat(result.charts()).hasSize(12);
        assertThat(result.charts().stream().mapToInt(c -> c.series().size()).sum()).isEqualTo(47);
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
        assertThat(result.charts().getFirst().series().getFirst().values()).hasSize(24).startsWith(0L, null, null);
        assertThat(result.charts().get(1).aggregation()).isEqualTo("LAST");
        verify(repository).aggregate(ownerTenant, project, from, to, 3, "SIMULATED");
    }
    @Test void noSourceDoesNotQueryOrInventZero() {
        member();
        when(repository.source(ownerTenant, project, to.minusSeconds(86400), to)).thenReturn("NONE");
        var result = service.get(project, 1);
        assertThat(result.source()).isEqualTo("NONE");
        assertThat(result.charts().getFirst().series().getFirst().values()).containsOnlyNulls();
        verify(repository, never()).aggregate(any(), any(), any(), any(), anyInt(), any());
    }
    @Test void deniesNonMemberBeforeReadingSamples() {
        when(projects.requireRoleInProject(project)).thenThrow(new IllegalStateException("denied"));
        assertThatThrownBy(() -> service.get(project, 1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
    @Test void rejectsUnsupportedWindowAndSelectsBoundedSteps() {
        member();
        assertThatThrownBy(() -> service.get(project, 2)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
        for (int days : new int[]{1,3,7,15,30}) {
            when(repository.source(ownerTenant, project, to.minusSeconds(days * 86400L), to)).thenReturn("NONE");
            assertThat(service.get(project, days).charts().getFirst().series().getFirst().values().size()).isLessThanOrEqualTo(30);
        }
    }
}
