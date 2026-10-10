package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppDeviceStatistics;
import com.things.link.enduser.domain.AppDeviceStatisticsRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** App统计先复验项目角色；设备关系过滤由统计仓储在数据库执行。 */
@Service
public class AppDeviceStatisticsService {
    private final AppUserRoleRepository roles;
    private final AppDeviceStatisticsRepository statistics;
    /** @param roles 项目角色仓储 @param statistics 授权统计仓储 */
    public AppDeviceStatisticsService(AppUserRoleRepository roles, AppDeviceStatisticsRepository statistics) {
        this.roles = roles;
        this.statistics = statistics;
    }

    /**
     * 返回可信令牌范围的四统计，角色失效统一401，不伪装成零设备。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param appUserId 可信终端用户
     * @return 同一数据库快照中的统计
     */
    @Transactional(readOnly = true)
    public AppDeviceStatistics read(UUID tenantId, UUID projectId, UUID appUserId) {
        var role = roles.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
        return statistics.read(tenantId, projectId, appUserId);
    }
}
