package com.things.link.assistant.application;

import com.things.link.alarm.application.ConsoleDeviceAlarmStatusService;
import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import com.things.link.device.application.ConsoleDeviceEvidence;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceEvidenceServiceTests {
    final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-03T00:00:00Z");
    final ConsoleDeviceEvidenceService devices = mock(ConsoleDeviceEvidenceService.class);
    final ConsoleDeviceAlarmStatusService alarms = mock(ConsoleDeviceAlarmStatusService.class);
    final DeviceEvidenceService service = new DeviceEvidenceService(devices, alarms, Clock.fixed(now, ZoneOffset.UTC));

    void arrange(UUID alarmModel) {
        when(devices.read(project, device, model, List.of("temperature"))).thenReturn(
                new ConsoleDeviceEvidence(device, model, "ONLINE", null, now, List.of()));
        when(alarms.read(project, List.of(device))).thenReturn(new DeviceAlarmStatusSnapshot(now,
                List.of(new DeviceAlarmStatusSnapshot.DeviceStatus(device, alarmModel, DeviceAlarmStatusSnapshot.State.ACTIVE))));
    }
    @Test void preservesSourceAndRechecksAfterAlarmRead() {
        arrange(model);
        var result = service.read(project, device, model, List.of("temperature"));
        assertThat(result.alarmSummary().state()).isEqualTo(DeviceAlarmStatusSnapshot.State.ACTIVE);
        var order = inOrder(devices, alarms);
        order.verify(devices).read(project, device, model, List.of("temperature"));
        order.verify(alarms).read(project, List.of(device));
        order.verify(devices).revalidate(project, device, model);
    }
    @Test void modelChangeNeverProducesMixedSnapshot() {
        arrange(UUID.randomUUID());
        assertThatThrownBy(() -> service.read(project, device, model, List.of("temperature"))).isInstanceOf(BusinessException.class);
    }
    @Test void revokedFinalPermissionRejectsPreviouslyCollectedEvidence() {
        arrange(model);
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).when(devices).revalidate(project, device, model);
        assertThatThrownBy(() -> service.read(project, device, model, List.of("temperature"))).isInstanceOf(BusinessException.class);
    }
    @Test void sourceFailureCannotBecomeNormal() {
        arrange(model);
        when(alarms.read(project, List.of(device))).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.read(project, device, model, List.of("temperature"))).isInstanceOf(IllegalStateException.class);
    }
}
