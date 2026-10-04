package com.things.link.dashboard.domain;

import java.time.Instant;

/**
 * 同一普通RLS语句观察的token与资源状态，不是可跨请求复用的授权缓存。
 * @param token 完整封存token @param dashboardRunnable 看板未删且仍有发布指针
 * @param dashboardVersionNumber token精确版本的版本号 @param databaseNow 数据库当前时刻
 */
public record DashboardShareRuntimeState(DashboardShareToken token, boolean dashboardRunnable,
        long dashboardVersionNumber, Instant databaseNow) { }
