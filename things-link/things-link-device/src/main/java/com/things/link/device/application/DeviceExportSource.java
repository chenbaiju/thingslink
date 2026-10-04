package com.things.link.device.application;

import java.time.Instant;
import java.util.UUID;

/** ADR0075 设备域导出白名单端口。 */
public interface DeviceExportSource {

    /**
     * 在调用方单一数据库快照内按稳定主键顺序流出有效设备档案。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目 ID
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamDevices(UUID tenantId, UUID projectId, DeviceSink sink);

    /**
     * {@code devices.jsonl} 的逐字段白名单事实，不含凭据、影子和软删内部字段。
     *
     * @param id 设备 ID
     * @param deviceTypeId 设备类型 ID
     * @param gatewayId 所属网关 ID
     * @param deviceKey 项目内稳定设备标识
     * @param name 名称
     * @param description 描述
     * @param status 在线状态
     * @param location 安装位置
     * @param lastOnlineAt 最近上线时刻
     * @param createdAt 创建时刻
     */
    record DeviceExportRow(UUID id, UUID deviceTypeId, UUID gatewayId, String deviceKey,
                           String name, String description, String status, String location,
                           Instant lastOnlineAt, Instant createdAt) {
    }

    /** 单行设备回调，禁止实现缓存全部结果。 */
    @FunctionalInterface
    interface DeviceSink {
        /** @param device 当前设备档案 */
        void accept(DeviceExportRow device);
    }
}
