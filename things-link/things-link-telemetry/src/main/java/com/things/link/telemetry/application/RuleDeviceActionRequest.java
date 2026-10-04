package com.things.link.telemetry.application;

import com.things.link.shared.message.DeviceCommandDispatch;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 规则域向设备操作端口提交的受信请求。
 *
 * @param actionId 规则动作稳定 ID，用于命令幂等与终态关联
 * @param projectId 项目隔离轴
 * @param requestedBy 不可变规则版本创建者
 * @param deviceId 目标设备
 * @param operationType 命令或属性设置
 * @param commandKey 命令键；属性设置为空
 * @param input 命令输入或属性对象
 */
public record RuleDeviceActionRequest(UUID actionId, UUID projectId, UUID requestedBy, UUID deviceId,
                                      DeviceCommandDispatch.OperationType operationType,
                                      String commandKey, JsonNode input) {
    /** 后台入口只接受完整、已冻结的动作定位。 */
    public RuleDeviceActionRequest {
        if (actionId == null || projectId == null || requestedBy == null || deviceId == null
                || operationType == null || input == null || !input.isObject()
                || (operationType == DeviceCommandDispatch.OperationType.COMMAND
                && (commandKey == null || commandKey.isBlank()))) {
            throw new IllegalArgumentException("规则设备动作请求不完整");
        }
    }
}
