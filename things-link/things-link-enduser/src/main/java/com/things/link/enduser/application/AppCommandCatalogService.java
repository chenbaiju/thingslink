package com.things.link.enduser.application;

import com.things.link.device.application.AppCommandCatalogDataPlaneService;
import com.things.link.device.application.AppCommandDefinition;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** ADR0110：App授权先于device投影，保持原角色与绑定含义。 */
@Service
public class AppCommandCatalogService {
    /** 唯一既有App设备授权入口。 */
    private final AppDeviceAccessService access;
    /** device拥有的公开投影。 */
    private final AppCommandCatalogDataPlaneService catalog;
    /** @param access App授权 @param catalog 设备目录 */
    public AppCommandCatalogService(AppDeviceAccessService access, AppCommandCatalogDataPlaneService catalog) {
        this.access = access;
        this.catalog = catalog;
    }
    /** @param projectId 项目 @param appUserId App用户 @param deviceId 设备 @return 完整有界目录 */
    @Transactional(readOnly = true)
    public List<AppCommandDefinition> list(UUID projectId, UUID appUserId, UUID deviceId) {
        access.requireCommandCatalogAccess(projectId, appUserId, deviceId);
        return catalog.list(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND));
    }
}
