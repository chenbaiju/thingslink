package com.things.link.integration.infrastructure;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
class WebhookRetentionMaintenanceTests {
    @Test void uncertainPurgeCommitNeverSpendsSameBudgetOnNextProject(){var state=mock(WebhookDeliveryState.class);var retention=mock(WebhookRetentionService.class);when(state.candidates()).thenReturn(List.of());var first=new WebhookRetentionRepository.Scope(UUID.randomUUID(),UUID.randomUUID());var next=new WebhookRetentionRepository.Scope(UUID.randomUUID(),UUID.randomUUID());when(retention.candidates()).thenReturn(List.of(first,next));when(retention.purge(first,500)).thenThrow(new IllegalStateException("uncertain commit"));new WebhookRetentionMaintenance(state,retention).tick();verify(retention).purge(first,500);verify(retention,never()).purge(eq(next),anyInt());}
    @Test void deliveryChangesReserveBudgetEvenWhenClaimCommitIsUncertain(){var state=mock(WebhookDeliveryState.class);var retention=mock(WebhookRetentionService.class);var c=new WebhookDeliveryRepository.Candidate(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var scope=new WebhookRetentionRepository.Scope(c.tenant(),c.project());when(state.candidates()).thenReturn(List.of(c));when(state.claim(c,false)).thenThrow(new IllegalStateException("uncertain commit"));when(retention.candidates()).thenReturn(List.of(scope));new WebhookRetentionMaintenance(state,retention).tick();verify(retention).purge(scope,498);verify(state,never()).claim(any(),eq(true));}
}
