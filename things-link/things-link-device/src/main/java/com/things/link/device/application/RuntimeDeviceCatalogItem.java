package com.things.link.device.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 分享合同§4有界设备目录的公开投影，不携带设备凭据或跨域持久实体。
 * @param deviceId 设备身份
 * @param name 当前设备名称
 * @param deviceStatus 当前在线状态
 * @param currentModelVersionId 当前精确模型版本
 * @param createdAt 稳定keyset创建时刻
 */
public record RuntimeDeviceCatalogItem(UUID deviceId, String name, String deviceStatus,
                                      UUID currentModelVersionId, Instant createdAt) { }
