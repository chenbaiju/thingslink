package com.things.link.enduser.domain;

import com.things.link.shared.page.CursorPage;
import java.util.Optional;
import java.util.UUID;

/** 将有效绑定与设备公开投影同SQL过滤，不在内存展开全量设备。 */
public interface AppDeviceReadRepository {
    /**
     * 读取一页授权设备。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param appUserId 可信终端用户
     * @param cursor 创建时间/标识游标
     * @param limit 页容量
     * @param query 名称或设备键字面子串
     * @param status 连接状态，可空
     * @return 设备页
     */
    CursorPage<AppDeviceDetails> list(UUID tenantId, UUID projectId, UUID appUserId,
                                     String cursor, int limit, String query, String status);
    /**
     * 未授权、已删除、跨范围均返回空，不泄漏存在性。
     * @param tenantId 可信租户
     * @param projectId 可信项目
     * @param appUserId 可信终端用户
     * @param deviceId 设备标识
     * @return 授权详情或空
     */
    Optional<AppDeviceDetails> detail(UUID tenantId, UUID projectId, UUID appUserId, UUID deviceId);
}
