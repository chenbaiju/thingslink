package com.things.link.device.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 物模型版本与绑定转换持久化端口；ingestion/telemetry 只能调用应用服务，不得直查 dev_* 表。 */
public interface ThingModelVersionRepository {
    /** 按已授权项目读取不可变完整版本，不接收草稿身份。 */
    Optional<ThingModelVersion> findPublished(UUID projectId, UUID versionId);

    /** @return 设备类型按语义版本降序的最新不可变版本 */
    Optional<ThingModelVersion> findLatest(UUID projectId, UUID deviceTypeId);

    /**
     * 插入版本并由 PostgreSQL jsonb 规范文本计算摘要，保证回填与在线发布算法一致。
     * @return 带最终摘要的不可变版本
     */
    ThingModelVersion create(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                             String versionNumber, ThingModelVersion.ChangeLevel changeLevel,
                             String modelSnapshot, Instant publishedAt);

    /**
     * 从已校验的草稿定义生成首个 1.0.0 快照，并把该类型的存量未绑定设备建立 INITIAL 绑定。
     * @param tenantId 类型归属租户 @param projectId 项目 ID @param deviceTypeId 已发布类型 ID
     * @return 新建的不可变初始版本
     */
    ThingModelVersion createInitialFromDefinitions(UUID tenantId, UUID projectId, UUID deviceTypeId);

    /** @param projectId 项目 ID @param deviceId 设备 ID @param versionNumber 设备声明版本 @return 版本与当前绑定 */
    Optional<ResolvedBinding> resolve(UUID projectId, UUID deviceId, String versionNumber);

    /**
     * 判定旧版本是否确实是当前绑定的直接来源，且 receivedAt 位于服务端转换窗口内。
     * @param projectId 项目 ID @param deviceId 设备 ID @param oldVersionId 旧版本 ID
     * @param currentVersionId 当前版本 ID @param receivedAt 服务端可信接收时刻 @param notBefore 窗口下界
     * @return 是否具备 history-only 资格
     */
    boolean wasDirectlyReplacedWithinWindow(UUID projectId, UUID deviceId, UUID oldVersionId,
                                            UUID currentVersionId, Instant receivedAt, Instant notBefore);

    /** @param projectId 项目 ID @param deviceId 设备 ID @param transitionKey 幂等键 @return 已存在转换 */
    Optional<BindingTransition> findTransition(UUID projectId, UUID deviceId, UUID transitionKey);

    /** @param projectId 项目 ID @param deviceId 设备 ID @return 加锁后的当前绑定 */
    Optional<ResolvedBinding> lockCurrent(UUID projectId, UUID deviceId);

    /** @param projectId 项目 ID @param deviceId 设备 ID @return 不加锁的当前绑定，仅供平台生成上行快照 */
    Optional<ResolvedBinding> findCurrent(UUID projectId, UUID deviceId);

    /**
     * 判定设备是否仍停留在初始绑定；一旦存在非 INITIAL 转换（升级/回滚/人工），
     * 省略 modelVersion 即不再可解析到“已绑定初始版本”，必须显式声明。
     * @param projectId 项目 ID @param deviceId 设备 ID @return 是否存在非首次绑定转换
     */
    boolean hasNonInitialTransition(UUID projectId, UUID deviceId);

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param versionId 目标版本 @return 目标版本 */
    Optional<ThingModelVersion> findById(UUID projectId, UUID deviceTypeId, UUID versionId);

    /** @param transition 不可变转换事实 */
    void appendTransition(BindingTransition transition);

    /** @return 是否按预期旧指针完成 CAS */
    boolean compareAndSetCurrent(UUID projectId, UUID deviceId, UUID expectedVersionId, UUID targetVersionId);

    /** 当前指针与声明版本的联合投影。 */
    record ResolvedBinding(UUID deviceId, UUID deviceTypeId, UUID currentVersionId, ThingModelVersion declaredVersion) { }

    /** 不可变绑定转换。 */
    record BindingTransition(UUID id, UUID tenantId, UUID projectId, UUID deviceId, UUID fromVersionId,
                             UUID toVersionId, UUID transitionKey, TransitionType transitionType,
                             Instant effectiveAt) {
        /** 转换原因只用于审计，不改变版本兼容判定。 */
        public enum TransitionType { /** 首次绑定。 */ INITIAL, /** 向前升级。 */ UPGRADE,
            /** 回滚。 */ ROLLBACK, /** 人工显式切换。 */ MANUAL }
    }
}
