package com.things.link.device.domain;

import java.util.UUID;

/**
 * PostgreSQL 当前值回源投影。
 *
 * @param deviceId 设备 ID
 * @param reported 当前属性 JSON 对象
 * @param reportedAt 与 reported 同键的采集时间 JSON 对象
 * @param version desired版本
 * @param reportedRevisions 逐属性规范序号对象
 * @param reportedModelVersion 逐属性写入模型来源对象
 */
public record DeviceShadowSnapshot(UUID deviceId, String reported, String reportedAt, int version,
                                   String reportedRevisions, String reportedModelVersion) {
    /** 旧快照缺少接受序号，不能借desired版本生成顺序。 */
    public DeviceShadowSnapshot(UUID deviceId, String reported, String reportedAt, int version) {
        this(deviceId, reported, reportedAt, version, "{}", "{}");
    }
}
