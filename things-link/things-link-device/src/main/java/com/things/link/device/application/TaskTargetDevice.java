package com.things.link.device.application;

import java.util.UUID;

/**
 * 任务域可使用的最小设备目标投影。
 *
 * <p>仅暴露稳定的设备 ID，避免任务模块依赖设备聚合、连接路由或物模型内部字段；真正投递时仍由
 * telemetry 经 device application 重新解析可信路由。</p>
 *
 * @param deviceId 项目内未删除设备 ID
 */
public record TaskTargetDevice(UUID deviceId) {
}
