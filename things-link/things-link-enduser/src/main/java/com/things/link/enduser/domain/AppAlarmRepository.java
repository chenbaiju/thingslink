package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 基于当前有效设备绑定的最小告警公开投影读取。 */
public interface AppAlarmRepository {
    /**
     * 在授权与过滤后按首次异常时间/标识键集读取。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param userId 可信终端用户
     * @param query 已校验筛选
     * @param beforeTime 已验签排序锚点，可空
     * @param beforeId 已验签标识锚点，可空
     * @param count 有界取数条数，含探测下一页的一条
     * @return 告警记录
     */
    List<AppAlarm> list(UUID tenantId, UUID projectId, UUID userId, AppAlarmQuery query,
                        Instant beforeTime, UUID beforeId, int count);
    /**
     * 读授权事故；不存在与不可见不区分。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param userId 可信终端用户
     * @param id 事故标识
     * @return 详情或空
     */
    Optional<AppAlarm> detail(UUID tenantId, UUID projectId, UUID userId, UUID id);
}
