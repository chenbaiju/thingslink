package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备类型聚合根。
 *
 * @param id 设备类型 ID
 * @param tenantId 归属租户 ID
 * @param projectId 归属项目 ID，也是数据库 RLS 隔离轴
 * @param typeKey 项目内唯一的稳定标识符
 * @param name 显示名称
 * @param deviceKind 设备分类
 * @param payloadProtocol 报文协议
 * @param networkType 通信方式
 * @param version 物模型版本
 * @param status 发布状态
 * @param productKey 一型一密产品标识（可空） @param productSecretHash 产品密钥哈希（可空）
 * @param createdAt 创建时刻
 */
public record DeviceType(UUID id, UUID tenantId, UUID projectId, String typeKey, String name,
                         DeviceKind deviceKind, PayloadProtocol payloadProtocol, NetworkType networkType,
                         int version, Status status,
                         String productKey, String productSecretHash, Instant createdAt) {

    /** 设备在拓扑中的分类；网关子设备不直接持有云端连接。 */
    public enum DeviceKind { /** 直接连接云平台。 */ DIRECT, /** 为子设备代理连接。 */ GATEWAY,
        /** 通过网关间接接入。 */ SUB_DEVICE }

    /** 设备报文的业务协议，不等同于 MQTT/HTTP/TCP 等传输通道。 */
    public enum PayloadProtocol { /** 平台标准属性/事件/命令规范。 */ STANDARD,
        /** Modbus RTU 报文透传。 */ MODBUS_RTU_PASSTHROUGH,
        /** Modbus TCP 报文透传。 */ MODBUS_TCP_PASSTHROUGH,
        /** 平台标准网关与拓扑协议。 */ STANDARD_GATEWAY,
        /** DTU 从机映射的 Modbus RTU 云网关。 */ MODBUS_RTU_CLOUD_GATEWAY }

    /** 设备物理或链路通信媒介；它不决定云端传输协议。 */
    public enum NetworkType { /** WiFi。 */ WIFI, /** 以太网。 */ ETHERNET,
        /** 蜂窝 2G。 */ CELLULAR_2G, /** 蜂窝 3G。 */ CELLULAR_3G,
        /** 蜂窝 4G。 */ CELLULAR_4G, /** 蜂窝 5G。 */ CELLULAR_5G,
        /** 窄带物联网。 */ NB_IOT, /** 低功耗蓝牙。 */ BLE, /** Zigbee。 */ ZIGBEE,
        /** LoRa。 */ LORA, /** RS485 总线。 */ RS485, /** 未枚举媒介。 */ OTHER }

    /** 物模型发布状态。 */
    public enum Status { /** 尚可编辑的草稿。 */ DRAFT, /** 已冻结的发布版本。 */ PUBLISHED }
}
