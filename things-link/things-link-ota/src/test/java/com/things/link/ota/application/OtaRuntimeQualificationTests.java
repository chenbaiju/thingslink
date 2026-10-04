package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceTargetSnapshotPort;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaTrustRepository;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 无账号运行资格的纯失败边界，真实租约与RLS仍由PG专项证明。 */
class OtaRuntimeQualificationTests {
    /** 唯一测试租户。 */ private static final UUID TENANT = new UUID(0, 1);
    /** 唯一测试项目。 */ private static final UUID PROJECT = new UUID(0, 2);
    /** 绑定物理资源键。 */ private final DataSource source = mock(DataSource.class);
    /** 实际被测边界的数据库门面。 */ private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    /** 旧作业终态后仍保留设备级冲突，不依赖当前作业存在。 */
    private final com.things.link.ota.domain.OtaRollbackRepository rollbacks = mock(com.things.link.ota.domain.OtaRollbackRepository.class);

    /** 不泄漏模拟事务状态到其他测试。 */
    @AfterEach void clearTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    /** 任意UUID不能在无事务或错RLS时取得后台资格。 */
    @Test void rejectsAbsentTransactionAndMismatchedScope() {
        when(jdbc.getDataSource()).thenReturn(source);
        assertThrows(IllegalStateException.class, () -> OtaRuntimeScope.require(jdbc, TENANT, PROJECT));
        transaction(false);
        assertThrows(IllegalStateException.class, () -> OtaRuntimeScope.require(jdbc, TENANT, PROJECT));
    }

    /** 旧凭证不能跨事务绑定资源使用。 */
    @Test void rejectsScopeAfterTransactionResourceChanges() {
        transaction(true);
        var scope = OtaRuntimeScope.require(jdbc, TENANT, PROJECT);
        TransactionSynchronizationManager.unbindResource(source);
        TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(mock(Connection.class)));
        assertThrows(IllegalStateException.class, scope::assertActive);
    }

    /** 即使没有任何设备报告，发布安全失败仍应安全暂停而非普通跳过。 */
    @Test void missingReportDoesNotMaskUnsafeRelease() {
        transaction(true);
        var releases = mock(OtaReleaseDownloadService.class);
        var baseline = mock(OtaTypeBaselineService.class);
        var service = qualification(releases, baseline);
        when(releases.lockRuntime(any(), any())).thenThrow(new IllegalArgumentException("test-only invalid signature"));
        var outcome = service.check(TENANT, PROJECT, new UUID(0, 3), new UUID(0, 4));
        assertEquals(OtaRuntimeQualification.Disposition.SECURITY, outcome.disposition());
        verify(releases).lockRuntime(any(), any());
    }

    /** 基础设施故障必须穿透事务，不得永久跳过设备或误判安全终态。 */
    @Test void infrastructureFailureIsNotConvertedToQualificationFailure() {
        transaction(true);
        var releases = mock(OtaReleaseDownloadService.class);
        var baseline = mock(OtaTypeBaselineService.class);
        var service = qualification(releases, baseline);
        when(baseline.lockRuntime(any(), any())).thenThrow(new DataAccessResourceFailureException("test database"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.check(TENANT, PROJECT, new UUID(0, 3), new UUID(0, 4)));
    }

    /** 真实范围校验后，历史冲突先于任何新发布资格返回安全拒绝。 */
    @Test void historicalDeviceConflictPreventsNewQualification() {
        transaction(true);
        UUID device = new UUID(0, 3);
        when(rollbacks.deviceConflicted(device)).thenReturn(true);
        var releases = mock(OtaReleaseDownloadService.class);
        var baseline = mock(OtaTypeBaselineService.class);
        var result = qualification(releases, baseline).check(TENANT, PROJECT, device, new UUID(0, 4));
        assertEquals(OtaRuntimeQualification.Disposition.SECURITY, result.disposition());
        assertEquals("DEVICE_ATOMIC_DIRECTION_CONFLICT", result.reason());
        org.mockito.Mockito.verifyNoInteractions(releases, baseline);
    }

    /** 创建缺报告情形，各数据库模型由独立mock提供。 */
    private OtaRuntimeQualification qualification(OtaReleaseDownloadService releases, OtaTypeBaselineService baseline) {
        var firmware = mock(OtaFirmware.class);
        when(firmware.deviceTypeId()).thenReturn(new UUID(0, 5));
        var firmwares = mock(OtaFirmwareRepository.class);
        when(firmwares.find(PROJECT, new UUID(0, 4), false)).thenReturn(Optional.of(firmware));
        var targets = mock(OtaDeviceTargetSnapshotPort.class);
        when(targets.lockExplicit(any(), any(), any(), any())).thenReturn(Optional.empty());
        var reports = mock(OtaDeviceReportRepository.class);
        return new OtaRuntimeQualification(jdbc, targets, mock(OtaDeviceIdentityPort.class), reports,
                firmwares, baseline, releases, mock(OtaTrustRepository.class), mock(OtaModelSnapshotPort.class), mock(OtaKnownSecurityFloor.class), new OtaRollbackDirectionGuard(rollbacks));
    }

    /** 模拟事务资源，仅测试guard分支，不声称数据库授权已成立。 */
    private void transaction(boolean matching) {
        when(jdbc.getDataSource()).thenReturn(source);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(TENANT.toString()), eq(PROJECT.toString())))
                .thenReturn(matching);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(mock(Connection.class)));
    }
}
