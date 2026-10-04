package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 经 Kafka 交给下行协议适配器的冻结命令信封。
 *
 * @param eventId Outbox 事件 ID，用于消费者至少一次去重
 * @param tenantId 租户 ID @param projectId 项目 ID
 * @param commandId 稳定业务命令 ID @param attemptId 本次尝试 ID @param attemptNo 尝试序号
 * @param targetDeviceId 目标设备 ID @param targetDeviceKey 目标设备键
 * @param connectionDeviceId 实际连接设备 ID @param connectionDeviceKey 实际连接设备键
 * @param projectKey MQTT 项目键 @param operationType 下行操作类型 @param commandKey 物模型命令键；属性设置为空
 * @param inputJson 已校验输入 JSON @param deadlineAt 本次响应截止时刻 @param traceId 链路 ID
 */
public record DeviceCommandDispatch(UUID eventId, UUID tenantId, UUID projectId,
                                    UUID commandId, UUID attemptId, int attemptNo,
                                    UUID targetDeviceId, String targetDeviceKey,
                                    UUID connectionDeviceId, String connectionDeviceKey,
                                    String projectKey, OperationType operationType, String commandKey, String inputJson,
                                    Instant deadlineAt, String traceId) {

    /** 信封进入 Kafka 前必须具备完整可信身份；属性设置唯一允许 commandKey 为空。 */
    public DeviceCommandDispatch {
        // S4 已持久化的 Outbox 载荷没有 operationType；升级期间必须继续按命令解释，不能把可恢复事件送入死信。
        operationType = operationType == null ? OperationType.COMMAND : operationType;
        if (eventId == null || tenantId == null || projectId == null || commandId == null || attemptId == null
                || attemptNo < 1 || targetDeviceId == null || targetDeviceKey == null || connectionDeviceId == null
                || connectionDeviceKey == null || projectKey == null || inputJson == null
                || deadlineAt == null || traceId == null || traceId.isBlank()
                || (operationType == OperationType.COMMAND && (commandKey == null || commandKey.isBlank()))) {
            throw new IllegalArgumentException("设备下行派发信封不完整");
        }
    }

    /** S9-2 冻结的两类设备下行操作；不能由租户输入扩展枚举。 */
    public enum OperationType {
        /** 物模型命令。 */ COMMAND,
        /** 物模型可下发属性集合。 */ PROPERTY_SET
    }

    /** 兼容 S4/S7 命令构造；既有调用默认仍是物模型命令。 */
    public DeviceCommandDispatch(UUID eventId, UUID tenantId, UUID projectId,
                                 UUID commandId, UUID attemptId, int attemptNo,
                                 UUID targetDeviceId, String targetDeviceKey,
                                 UUID connectionDeviceId, String connectionDeviceKey,
                                 String projectKey, String commandKey, String inputJson,
                                 Instant deadlineAt, String traceId) {
        this(eventId, tenantId, projectId, commandId, attemptId, attemptNo, targetDeviceId, targetDeviceKey,
                connectionDeviceId, connectionDeviceKey, projectKey, OperationType.COMMAND, commandKey,
                inputJson, deadlineAt, traceId);
    }
}
