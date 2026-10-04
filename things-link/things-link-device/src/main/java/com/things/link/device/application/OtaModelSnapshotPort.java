package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** device拥有的OTA模型只读端口；消费域不得直接读取dev表或用普通版本摘要推断类型绑定。 */
@FunctionalInterface
public interface OtaModelSnapshotPort {
    /**
     * 精确核对项目、类型和模型，且类型已发布并具备产品标识；调用方负责自身项目授权。
     * 不存在、错项目、错类型、未发布或RLS不可见均为空，不返回原始schema或产品密钥。
     */
    Optional<OtaModelSnapshot> find(UUID projectId, UUID deviceTypeId, UUID thingModelVersionId);
}
