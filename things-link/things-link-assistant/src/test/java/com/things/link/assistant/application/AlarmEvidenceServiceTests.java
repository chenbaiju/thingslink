package com.things.link.assistant.application;

import com.things.link.alarm.application.*;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmEvidenceServiceTests {
    final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID();
    final Instant at = Instant.parse("2026-10-04T00:00:00Z");
    final ConsoleAlarmDeviceQueryService alarms = mock(ConsoleAlarmDeviceQueryService.class);
    final ConsoleDeviceEvidenceService devices = mock(ConsoleDeviceEvidenceService.class);
    final AlarmEvidenceService service = new AlarmEvidenceService(alarms, devices, Clock.fixed(at, ZoneOffset.UTC));
    AlarmDeviceQueryItem item(UUID device, String severity) {
        return new AlarmDeviceQueryItem(UUID.randomUUID(), device, "禁止回传自由文本", severity,
                "ACTIVE", "UNACKNOWLEDGED", at.minusSeconds(5), at.minusSeconds(4), null, null, at, 0);
    }
    void source(CursorPage<AlarmDeviceQueryItem> page) {
        when(alarms.query(any(), anyList(), anyList(), anyList(), anyList(), any(), any())).thenReturn(page);
    }
    @Test void projectsOnlyClosedFieldsAndRevalidatesAroundSourceRead() {
        var source = item(device, "MAJOR"); source(CursorPage.of(List.of(source), "signed-cursor"));
        var value = service.read(project, device, model, null, 1);
        assertThat(value.items()).hasSize(1);
        assertThat(value.items().getFirst().id()).isEqualTo(source.id());
        assertThat(value.items().getFirst().clearedAt()).isNull();
        assertThat(value.sourceModelState()).isEqualTo("NOT_PROVIDED");
        assertThat(value.currentModelVersionId()).isEqualTo(model);
        assertThat(value.toString()).doesNotContain(source.alarmType());
        assertThat(value.hasMore()).isTrue();
        var order = inOrder(devices, alarms);
        order.verify(devices).revalidate(project, device, model);
        order.verify(alarms).query(eq(project), eq(List.of(new AlarmDeviceExpectation(device, model))),
                argThat(values -> values.equals(Arrays.stream(AlarmInstance.ConditionState.values()).map(Enum::name).toList())),
                argThat(values -> new HashSet<>(values).equals(new HashSet<>(Arrays.stream(AlarmInstance.AckState.values()).map(Enum::name).toList()))),
                argThat(values -> new HashSet<>(values).equals(new HashSet<>(Arrays.stream(AlarmRule.Severity.values()).map(Enum::name).toList()))),
                isNull(), eq(1));
        order.verify(devices).revalidate(project, device, model);
    }
    @Test void emptyPageDoesNotInventNormalStateOrSourceModel() {
        source(CursorPage.last(List.of()));
        var value = service.read(project, device, model, null, null);
        assertThat(value.items()).isEmpty(); assertThat(value.hasMore()).isFalse();
        assertThat(value.nextCursor()).isNull(); assertThat(value.limit()).isEqualTo(20);
        assertThat(value.sourceModelState()).isEqualTo("NOT_PROVIDED");
    }
    @Test void rejectsInvalidBoundsBeforeSourceRead() {
        for (int limit : List.of(0, 51))
            assertThatThrownBy(() -> service.read(project, device, model, null, limit)).isInstanceOf(BusinessException.class);
        for (String cursor : List.of(" ", "x".repeat(4097)))
            assertThatThrownBy(() -> service.read(project, device, model, cursor, 1)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(devices, alarms);
    }
    @Test void sourceFailureIsNotSuccessfulEmptyPage() {
        when(alarms.query(any(), anyList(), anyList(), anyList(), anyList(), any(), any())).thenThrow(new IllegalStateException("source failure"));
        assertThatThrownBy(() -> service.read(project, device, model, null, 1)).isInstanceOf(IllegalStateException.class);
        verify(devices, times(1)).revalidate(project, device, model);
    }
    @Test void revocationAfterReadCannotReturnCollectedFacts() {
        source(CursorPage.last(List.of(item(device, "INFO"))));
        doNothing().doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).when(devices).revalidate(project, device, model);
        assertThatThrownBy(() -> service.read(project, device, model, null, 1)).isInstanceOf(BusinessException.class);
    }
    @Test void refusesForeignDeviceAndUnrecognizedEnumFacts() {
        for (var item : List.of(item(UUID.randomUUID(), "INFO"), item(device, "MYSTERY"))) {
            source(CursorPage.last(List.of(item)));
            assertThatThrownBy(() -> service.read(project, device, model, null, 1)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void refusesOverflowDuplicateIdsAndInconsistentCursor() {
        var item = item(device, "INFO");
        source(CursorPage.last(List.of(item, item)));
        assertThatThrownBy(() -> service.read(project, device, model, null, 2)).isInstanceOf(IllegalStateException.class);
        for (var page : List.of(CursorPage.last(List.of(item, item)), new CursorPage<>(List.of(item), null, true),
                new CursorPage<>(List.of(item), "cursor", false))) {
            source(page);
            assertThatThrownBy(() -> service.read(project, device, model, null, 1)).isInstanceOf(IllegalStateException.class);
        }
    }
}
