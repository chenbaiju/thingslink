package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Console普通项目成员目录；与分享的明确候选集合端口分离。 */
public interface ConsoleDeviceRuntimeCatalogRepository {
    /**
     * @param projectId 已建立真实tenant/project RLS的项目
     * @param modelVersionId 精确模型过滤
     * @param beforeTime 可选分页时刻
     * @param beforeId 与时刻成对的稳定ID
     * @param limit 含探测行的1..51条预算
     * @return 先授权/模型/软删过滤后createdAt/id倒序的有限行
     */
    List<DeviceRuntimeCatalogRepository.Item> findConsole(UUID projectId, UUID modelVersionId,
                                                         Instant beforeTime, UUID beforeId, int limit);
}
