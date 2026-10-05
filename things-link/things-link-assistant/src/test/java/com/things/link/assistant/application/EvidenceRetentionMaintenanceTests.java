package com.things.link.assistant.application;

import com.things.link.assistant.domain.EvidenceRetentionRepository.Scope;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceRetentionMaintenanceTests {
    final EvidenceRetentionService service=mock(EvidenceRetentionService.class);
    final EvidenceRetentionMaintenance maintenance=new EvidenceRetentionMaintenance(service);
    final Scope first=new Scope(UUID.randomUUID(),UUID.randomUUID()),second=new Scope(UUID.randomUUID(),UUID.randomUUID());
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void productionDefaultEnabledAndExplicitDisableRemovesMaintenance() {
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(EvidenceRetentionService.class,()->service).withUserConfiguration(EvidenceRetentionMaintenance.class);
        context.run(c->assertThat(c).hasSingleBean(EvidenceRetentionMaintenance.class));
        context.withPropertyValues("things-link.assistant.evidence-retention.enabled=false")
                .run(c->assertThat(c).doesNotHaveBean(EvidenceRetentionMaintenance.class));
    }
    @Test void totalBudgetStopsAtFiveHundredAndContinuesWithProjectCursor() {
        when(service.candidates(null)).thenReturn(List.of(first,second));
        when(service.purge(first,500)).thenReturn(500);
        maintenance.tick();verify(service,never()).purge(eq(second),anyInt());
        when(service.candidates(first.projectId())).thenReturn(List.of(second));
        maintenance.tick();verify(service).candidates(first.projectId());verify(service).purge(second,500);
    }
    @Test void remainingBudgetIsSharedAcrossProjects() {
        when(service.candidates(null)).thenReturn(List.of(first,second));
        when(service.purge(first,500)).thenReturn(301);when(service.purge(second,199)).thenReturn(199);
        maintenance.tick();verify(service).purge(second,199);
    }
    @Test void uncertainFailureDoesNotReuseBudgetAndRetriesSameProject() {
        when(service.candidates(null)).thenReturn(List.of(first,second));
        when(service.purge(first,500)).thenThrow(new IllegalStateException("private input"));
        maintenance.tick();maintenance.tick();verify(service,never()).purge(eq(second),anyInt());
        verify(service,times(2)).candidates(null);verify(service,times(2)).purge(first,500);
    }
    @Test void emptyPageResetsCursorAndCallerIdentityAlwaysRestored() {
        var caller=new TenantScope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());TenantContext.set(caller);
        when(service.candidates(null)).thenAnswer(call->{assertThat(TenantContext.current()).isEmpty();return List.of(first);});
        when(service.candidates(first.projectId())).thenReturn(List.of());
        maintenance.tick();assertThat(TenantContext.current()).contains(caller);
        maintenance.tick();maintenance.tick();verify(service,times(2)).candidates(null);
        assertThat(TenantContext.current()).contains(caller);
    }
    @Test void scanFailureAndInvalidReceiptStopWithoutAdvancingCursor() {
        var caller=new TenantScope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());TenantContext.set(caller);
        when(service.candidates(null)).thenThrow(new IllegalArgumentException("secret"));
        maintenance.tick();assertThat(TenantContext.current()).contains(caller);verify(service,never()).purge(any(),anyInt());
        doReturn(List.of(first,second)).when(service).candidates(null);when(service.purge(first,500)).thenReturn(501);
        maintenance.tick();verify(service,never()).purge(eq(second),anyInt());assertThat(TenantContext.current()).contains(caller);
    }
}
