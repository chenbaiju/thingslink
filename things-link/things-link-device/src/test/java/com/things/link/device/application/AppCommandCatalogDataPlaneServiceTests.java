package com.things.link.device.application;

import com.things.link.device.domain.AppCommandCatalogRepository;
import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** ADR0110：候选哨兵必须整体失败，不能被误读成空Schema或截断目录。 */
class AppCommandCatalogDataPlaneServiceTests {
    /** 设备仓储隔离替身。 */
    private final DeviceRepository devices = mock(DeviceRepository.class);
    /** 有界查询端口。 */
    private final AppCommandCatalogRepository catalog = mock(AppCommandCatalogRepository.class);
    /** 被测公开端口。 */
    private final AppCommandCatalogDataPlaneService service = new AppCommandCatalogDataPlaneService(devices, catalog);
    /** 固定项目身份。 */
    private final UUID project = UUID.randomUUID();
    /** 固定设备身份。 */
    private final UUID deviceId = UUID.randomUUID();
    /** 固定真实设备类型。 */
    private final UUID typeId = UUID.randomUUID();

    /** 未命中设备不能继续读取类型目录。 */
    @Test void missingDeviceHasNoCatalogRead() {
        when(devices.findById(project, deviceId)).thenReturn(Optional.empty());
        assertThat(service.list(project, deviceId)).isEmpty();
        verifyNoInteractions(catalog);
    }
    /** null Schema是合法字段，不等于超限哨兵。 */
    @Test void preservesNullSchemasAndCompleteHundredEntries() {
        seed();
        when(catalog.findBounded(project, typeId)).thenReturn(Collections.nCopies(100, entry(false)));
        assertThat(service.list(project, deviceId).orElseThrow()).hasSize(100)
                .allSatisfy(item -> assertThat(item.inputSchema()).isNull());
    }
    /** SQL的101项用于证明不完整，不能向客户端返回前100项。 */
    @Test void hundredAndFirstEntryRejectsWholeCatalog() {
        seed();
        when(catalog.findBounded(project, typeId)).thenReturn(Collections.nCopies(101, entry(false)));
        rejects();
    }
    /** 大Schema未加载到Java，哨兵仍须向外传播失败。 */
    @Test void oversizedSchemaRejectsWholeCatalog() {
        seed();
        when(catalog.findBounded(project, typeId)).thenReturn(List.of(entry(true)));
        rejects();
    }
    /** 建立真实类型投影而非接受外部typeId。 */
    private void seed() {
        Device device = mock(Device.class);
        when(device.deviceTypeId()).thenReturn(typeId);
        when(devices.findById(project, deviceId)).thenReturn(Optional.of(device));
    }
    /** @param oversized SQL超限事实 @return 单项安全候选 */
    private AppCommandCatalogRepository.Entry entry(boolean oversized) {
        return new AppCommandCatalogRepository.Entry("restart", "重启", null, null, null, 30, oversized);
    }
    /** 只接受冻结的503业务码，不把异常视为成功空目录。 */
    private void rejects() {
        assertThatThrownBy(() -> service.list(project, deviceId)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(30064));
    }
}
