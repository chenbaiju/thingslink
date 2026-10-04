package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 无可信项目范围时由受限数据库函数返回的应用定位事实。
 *
 * <p>S12-2a2a只允许首跳能力暴露建立RLS所需的三项身份；展示名称、版本正文与项目公开键必须在
 * 建立可信事务范围后由各自领域重新读取，不能借SECURITY DEFINER函数绕过普通业务投影。</p>
 *
 * @param tenantId 应用所属项目的租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 应用内部ID
 */
public record ApplicationRuntimeLocation(UUID tenantId, UUID projectId, UUID applicationId) {

    /** 拒绝数据库不可能返回的残缺定位事实，避免调用方用空身份建立RLS范围。 */
    public ApplicationRuntimeLocation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
    }
}
