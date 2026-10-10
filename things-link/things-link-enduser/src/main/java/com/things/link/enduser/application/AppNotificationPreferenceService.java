package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppNotificationPreference;
import com.things.link.enduser.domain.AppNotificationPreferenceRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 当前项目只限定入口资格，偏好事实覆盖同账号全部已授权项目。 */
@Service
public class AppNotificationPreferenceService {
    private final AppAccountService accounts;
    private final AppNotificationPreferenceRepository preferences;
    private final AppProjectWriteGuard projectWriteGuard;
    private final AppUserRepository users;
    /**
     * @param accounts 本人身份与角色复核
     * @param preferences 账号偏好仓储
     * @param projectWriteGuard 原事务项目写许可
     * @param users 稳定用户行锁
     */
    public AppNotificationPreferenceService(AppAccountService accounts, AppNotificationPreferenceRepository preferences,
            AppProjectWriteGuard projectWriteGuard, AppUserRepository users) {
        this.accounts = accounts;
        this.preferences = preferences;
        this.projectWriteGuard = projectWriteGuard;
        this.users = users;
    }
    /**
     * 读取本人偏好及当前入口可编辑性，不承诺实际通道已接入。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param userId 可信终端用户
     * @return 偏好与只读项目状态
     */
    @Transactional(readOnly = true)
    public PreferenceView read(UUID tenantId, UUID projectId, UUID userId) {
        var account = accounts.read(tenantId, projectId, userId);
        return new PreferenceView(preferences.read(tenantId, userId), account.passwordChangeAllowed());
    }
    /**
     * 项目许可先于用户锁，锁后复验身份与版本；旧版本不覆盖另一安装的决定。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param userId 可信终端用户
     * @param enabled 目标App告警偏好
     * @param expectedRevision 调用者刚读取的版本
     * @return 已提交事务中的新事实；不变请求不推进版本
     */
    @Transactional
    public PreferenceView update(UUID tenantId, UUID projectId, UUID userId, boolean enabled, long expectedRevision) {
        projectWriteGuard.requireWritable(tenantId, projectId);
        users.lockByIdAndTenant(tenantId, userId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        accounts.read(tenantId, projectId, userId);
        var previous = preferences.read(tenantId, userId);
        if (previous.revision() != expectedRevision) {
            throw new BusinessException(EndUserErrorCode.NOTIFICATION_PREFERENCE_CONFLICT);
        }
        if (previous.appPushEnabled() == enabled) return new PreferenceView(previous, true);
        if (previous.revision() == Long.MAX_VALUE) {
            throw new BusinessException(EndUserErrorCode.NOTIFICATION_PREFERENCE_CONFLICT);
        }
        var next = new AppNotificationPreference(enabled, previous.revision() + 1);
        preferences.save(tenantId, userId, next);
        return new PreferenceView(next, true);
    }
    /** 偏好与入口资格分别表达，通道状态另行展示。 */
    public record PreferenceView(AppNotificationPreference preference, boolean editable) { }
}
