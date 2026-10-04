package com.things.link.device.application;

import com.things.link.device.domain.AppCommandCatalogRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** App命令目录公开端口；调用方须先核App控制授权，同事务RLS及显式项目条件继续兜底。 */
@Service
public class AppCommandCatalogDataPlaneService {
    /** 设备真实类型来源，禁止客户端自行传类型。 */
    private final DeviceRepository devices;
    /** 有界命令投影。 */
    private final AppCommandCatalogRepository catalog;
    /** @param devices 设备仓储 @param catalog 有界目录 */
    public AppCommandCatalogDataPlaneService(DeviceRepository devices, AppCommandCatalogRepository catalog) {
        this.devices = devices;
        this.catalog = catalog;
    }
    /** @param projectId 已授权项目 @param deviceId 已授权设备 @return 空Optional表示设备不存在 */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<List<AppCommandDefinition>> list(UUID projectId, UUID deviceId) {
        return devices.findById(projectId, deviceId).map(device -> {
            if (device.deviceTypeId() == null) return List.<AppCommandDefinition>of();
            List<AppCommandCatalogRepository.Entry> entries = catalog.findBounded(projectId, device.deviceTypeId());
            if (entries.size() > 100 || entries.stream().anyMatch(AppCommandCatalogRepository.Entry::oversized)) {
                throw unavailable();
            }
            return entries.stream().map(value -> new AppCommandDefinition(value.commandKey(), value.name(),
                    value.description(), value.inputSchema(), value.outputSchema(), value.timeoutSeconds())).toList();
        });
    }
    /** 跨模块统一使用公开错误工厂，不让enduser依赖device.domain。 */
    public static BusinessException unavailable() {
        return new BusinessException(DeviceErrorCode.COMMAND_CATALOG_UNAVAILABLE);
    }
}
