package com.things.link.assistant.application;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.telemetry.application.ConsoleHistoryEvidence;
import com.things.link.telemetry.application.ConsoleHistoryEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class HistoryEvidenceServiceTests {
    final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID();
    final Instant to = Instant.parse("2026-10-04T00:00:00Z"), from = to.minusSeconds(3600);
    final ConsoleHistoryEvidenceService history = mock(ConsoleHistoryEvidenceService.class);
    final ConsoleDeviceEvidenceService devices = mock(ConsoleDeviceEvidenceService.class);
    final HistoryEvidenceService service = new HistoryEvidenceService(history, devices);
    @Test void retainsEmptyStateAndRevalidatesAfterRead() {
        var evidence = new ConsoleHistoryEvidence(project, device, model, "temperature", from, to, from, to,
                false, "RAW", "RAW", "AVG", to, ConsoleHistoryEvidence.State.NO_POINTS, List.of());
        when(history.read(project, device, model, "temperature", from, to)).thenReturn(evidence);
        assertThat(service.read(project, device, model, "temperature", from, to)).isSameAs(evidence);
        var order = inOrder(history, devices);
        order.verify(history).read(project, device, model, "temperature", from, to);
        order.verify(devices).revalidate(project, device, model);
    }
    @Test void neverReturnsCollectedDataAfterRevocation() {
        when(history.read(project, device, model, "temperature", from, to)).thenReturn(
                new ConsoleHistoryEvidence(project, device, model, "temperature", from, to, from, to,
                        false, "RAW", "RAW", "AVG", to, ConsoleHistoryEvidence.State.NO_POINTS, List.of()));
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).when(devices).revalidate(project, device, model);
        assertThatThrownBy(() -> service.read(project, device, model, "temperature", from, to)).isInstanceOf(BusinessException.class);
    }
    @Test void sourceFailureCannotBecomeEmptySuccess() {
        when(history.read(project, device, model, "temperature", from, to)).thenThrow(new IllegalStateException("source failure"));
        assertThatThrownBy(() -> service.read(project, device, model, "temperature", from, to)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(devices);
    }
}
