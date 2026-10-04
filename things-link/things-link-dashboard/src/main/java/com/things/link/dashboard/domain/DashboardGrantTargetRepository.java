package com.things.link.dashboard.domain;

import java.util.UUID;

/**
 * 为终端用户看板授权事务锁定稳定目录的持久端口。
 *
 * <p>端口只按完整三轴身份锁定未软删目录，不读取草稿、当前发布指针或版本，因此未发布和已撤回
 * 看板仍可成为授权目标。调用方负责项目许可、用户归属与锁序，本端口不建立RLS范围或取得用户锁。</p>
 */
public interface DashboardGrantTargetRepository {

    /**
     * 以共享行锁稳定看板目录，阻塞并发软删且允许不同用户并发授权同一看板。
     *
     * @param tenantId 已由调用方项目许可确认的租户ID
     * @param projectId 已由调用方项目许可确认的项目ID
     * @param dashboardId 待授权稳定看板ID
     * @return 完整身份对应未软删目录被锁定时返回true
     * @throws IllegalStateException 缺少原非只读RC/RU事务或连接处于自动提交
     */
    boolean lockForGrant(UUID tenantId, UUID projectId, UUID dashboardId);
}
