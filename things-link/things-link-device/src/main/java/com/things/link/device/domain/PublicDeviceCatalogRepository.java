package com.things.link.device.domain;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** ADR0173公开目录；调用方先完成Key授权与同事务RLS。 */
public interface PublicDeviceCatalogRepository {
    /** 可选精确模型过滤在分页之前应用，最多51行。 */
    List<DeviceRuntimeCatalogRepository.Item> findPublic(UUID projectId, UUID modelVersionId, Instant beforeTime, UUID beforeId, int limit);
}
