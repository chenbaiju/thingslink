package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.device.application.ProjectDeviceStatisticsService;
import com.things.link.project.application.ProjectService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProjectAlarmStatisticsServiceTests {
    @Test
    void paginatesBeyondOneThousandAndFiltersDeletedDevicesWithoutTruncating() {
        var repo = mock(AlarmInstanceRepository.class);
        var projects = mock(ProjectService.class);
        var devices = mock(ProjectDeviceStatisticsService.class);
        UUID project = UUID.randomUUID();
        var first = IntStream.range(0,1000).mapToObj(i -> new AlarmInstanceRepository.DeviceSeverity(
                new UUID(0,i+1), AlarmRule.Severity.INFO)).toList();
        var tail = new AlarmInstanceRepository.DeviceSeverity(new UUID(0,1001), AlarmRule.Severity.CRITICAL);
        when(repo.activeDeviceSeverities(project, null, 1000)).thenReturn(first);
        when(repo.activeDeviceSeverities(project, first.getLast().deviceId(), 1000)).thenReturn(List.of(tail));
        when(devices.existingIds(eq(project), anyList())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(1);
            return ids.stream().filter(id -> !id.equals(first.getFirst().deviceId()))
                    .collect(java.util.stream.Collectors.toSet());
        });
        var result = new ProjectAlarmStatisticsService(repo, projects, devices).snapshot(project);
        assertThat(result).isEqualTo(new ProjectAlarmStatistics(1,0,0,0,999));
        assertThat(result.activeDevices()).isEqualTo(1000);
        verify(repo, times(2)).activeDeviceSeverities(eq(project), any(), eq(1000));
        verify(projects).requireRoleInProject(project);
    }

    @Test
    void noActiveAlarmsDoesNotQueryDeviceIds() {
        var repo = mock(AlarmInstanceRepository.class);
        var projects = mock(ProjectService.class);
        var devices = mock(ProjectDeviceStatisticsService.class);
        UUID project = UUID.randomUUID();
        when(repo.activeDeviceSeverities(project, null, 1000)).thenReturn(List.of());
        assertThat(new ProjectAlarmStatisticsService(repo, projects, devices).snapshot(project).activeDevices()).isZero();
        verifyNoInteractions(devices);
    }
}
