package com.things.link.dashboard.domain;

import java.util.UUID;

/** @param tenantId 精确capability定位取得的可信租户 @param projectId 同一token的可信项目 */
public record DashboardShareRuntimeIdentity(UUID tenantId, UUID projectId) { }
