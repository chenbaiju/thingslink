package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 看板运行数据使用的有界设备、模型与PG当前值仓储。
 *
 * <p>每个入口只接受最多20台设备或200个精确属性组合，并在单条普通RLS SQL内形成响应所需观察；
 * 不能改用全项目扫描或Redis热副本。</p>
 */
public interface DeviceRuntimeRepository {

    /** 原非只读事务内锁定存在设备，保持其删除与模型事实直到出站接管。 */
    default int lockDevices(UUID projectId,List<UUID> ids){throw new UnsupportedOperationException("未实现设备出站锁");}

    /** @param projectId 可信项目 @param requests 最多20个设备模型请求 @return 按请求位置的设备事实 */
    List<DeviceFact> inspect(UUID projectId, List<DeviceRequest> requests);

    /** @param projectId 可信项目 @param requests 最多20个设备模型请求 @return 同次设备和模型事实 */
    List<DeviceModelFact> snapshot(UUID projectId, List<DeviceRequest> requests);

    /** @param projectId 可信项目 @param requests 最多200个设备属性组合 @return 同次设备、模型和影子事实 */
    List<CurrentValueFact> currentValues(UUID projectId, List<PropertyRequest> requests);

    /** @param deviceId 设备 @param expectedModelVersionId 预期模型 @param position 请求位置 */
    record DeviceRequest(UUID deviceId, UUID expectedModelVersionId, int position) { }

    /** @param deviceId 设备 @param expectedModelVersionId 预期模型 @param propertyKey 属性 @param devicePosition 设备位置 @param propertyPosition 属性位置 */
    record PropertyRequest(UUID deviceId, UUID expectedModelVersionId, String propertyKey,
                           int devicePosition, int propertyPosition) { }

    /** @param deviceId 请求设备 @param position 请求位置 @param visible 是否可见 @param currentModelVersionId 当前模型 @param name 名称 @param status 设备状态 @param lastOnlineAt 最近在线 */
    record DeviceFact(UUID deviceId, int position, boolean visible, UUID currentModelVersionId,
                      String name, String status, Instant lastOnlineAt) { }

    /**
     * 设备快照及匹配时的不可变模型事实。
     *
     * @param device 设备事实
     * @param modelSnapshot 模型规范JSON；模型匹配时必有
     * @param digestAlgorithm 持久摘要算法
     * @param storedDigest 持久摘要
     * @param calculatedDigest 同条SQL复算摘要
     * @param profile 模型Profile
     */
    record DeviceModelFact(DeviceFact device, String modelSnapshot, String digestAlgorithm,
                           String storedDigest, String calculatedDigest, String profile) { }

    /**
     * 一个请求属性组合的同条SQL事实。
     *
     * @param device 设备事实
     * @param propertyKey 属性键
     * @param propertyPosition 属性位置
     * @param modelSnapshot 模型规范JSON
     * @param digestAlgorithm 模型摘要算法
     * @param storedDigest 持久模型摘要
     * @param calculatedDigest 同条SQL复算摘要
     * @param profile 模型Profile
     * @param valuePresent reported是否含键
     * @param valueJson 原始jsonb值
     * @param occurredAtText 属性采集时刻文本
     * @param reportedModelVersionText 属性来源模型UUID文本
     */
    record CurrentValueFact(DeviceFact device, String propertyKey, int propertyPosition,
                            String modelSnapshot, String digestAlgorithm, String storedDigest,
                            String calculatedDigest, String profile, boolean valuePresent,
                            String valueJson, String occurredAtText, String reportedModelVersionText) { }
}
