package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaDeviceReportState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 数据库与进程时钟偏移不能让新报告超前于数据库来源捕获时间。 */
class OtaDeviceReportClockTests {
    /** 固定过去的数据库时钟，证明新鲜度和持久时间都不依赖当前进程时钟。 */
    @Test void usesDatabaseClockAndPreservesPreviousAcceptanceFloor() throws Exception {
        try (var input = getClass().getResourceAsStream("/ota/device-report-v1.json")) {
            byte[] payload = input.readAllBytes();
            var decoded = new OtaDeviceReportCodec().decode(payload);
            var report = decoded.value();
            var identity = new AuthenticatedDeviceIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
            var devices = mock(OtaDeviceIdentityPort.class);
            var reports = mock(OtaDeviceReportRepository.class);
            var lifecycle = mock(ProjectLifecycleAccessService.class);
            var floors = mock(OtaKnownSecurityFloor.class);
            var service = new OtaDeviceReportIngestionService(devices, reports, lifecycle,
                    mock(TransactionLocalRlsScope.class), floors);
            when(lifecycle.lockActiveForWrite(identity.tenantId(), identity.projectId())).thenReturn(true);
            when(devices.lockCurrent(identity)).thenReturn(Optional.of(new OtaDeviceIdentity(identity.tenantId(),
                    identity.projectId(), identity.deviceId(), UUID.randomUUID(), "product", 2,
                    report.thingModelVersionId(), report.thingModelSchemaDigestAlgorithm(),
                    report.thingModelSchemaDigest(), report.propertyProfile(), null)));
            when(devices.credentialValid(identity)).thenReturn(true);
            when(floors.accepts(any(), any(), any(), any())).thenReturn(true);
            Instant databaseNow = Instant.parse("2020-01-01T00:00:00.123456Z");
            when(reports.currentTime()).thenReturn(databaseNow);
            when(reports.find(identity.projectId(), identity.deviceId(), true, false)).thenReturn(Optional.empty());
            assertThat(service.accept(identity, payload, databaseNow)).isEqualTo(OtaDeviceReportIngestionService.Outcome.ACCEPTED);
            var created = ArgumentCaptor.forClass(OtaDeviceReportState.class);
            verify(reports).create(created.capture());
            assertThat(created.getValue().acceptedAt()).isEqualTo(databaseNow);

            Instant previousFuture = databaseNow.plusMillis(74);
            var previous = new OtaDeviceReportState(identity.tenantId(), identity.projectId(), identity.deviceId(),
                    1, report.reportSequence(), 1, report.committedSecurityVersion(), decoded.canonical(),
                    decoded.sha256(), databaseNow, previousFuture);
            when(reports.find(identity.projectId(), identity.deviceId(), true, false)).thenReturn(Optional.of(previous));
            when(reports.replace(org.mockito.ArgumentMatchers.eq(1L), any())).thenReturn(true);
            assertThat(service.accept(identity, payload, databaseNow)).isEqualTo(OtaDeviceReportIngestionService.Outcome.ACCEPTED);
            var replaced = ArgumentCaptor.forClass(OtaDeviceReportState.class);
            verify(reports).replace(org.mockito.ArgumentMatchers.eq(1L), replaced.capture());
            assertThat(replaced.getValue().acceptedAt()).isEqualTo(previousFuture);
        }
    }
}
