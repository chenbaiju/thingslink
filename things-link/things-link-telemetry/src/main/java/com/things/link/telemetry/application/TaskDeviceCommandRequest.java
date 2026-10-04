package com.things.link.telemetry.application;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 任务域向命令域提交单个设备命令的受信请求。
 *
 * <p>任务执行已经在 task 模块完成权限、调度租约和目标快照校验；本契约刻意不带 tenantId，
 * 命令域必须从设备/项目权威数据解析归属，避免后台线程伪造 HTTP 租户上下文。</p>
 *
 * @param executionId 任务执行 ID，用于稳定幂等键与追溯
 * @param projectId 项目隔离轴
 * @param requestedBy 任务执行冻结的创建者账号，满足命令审计外键
 * @param deviceId 业务目标设备 ID
 * @param commandKey 物模型命令标识
 * @param input 已冻结的命令输入对象
 */
public record TaskDeviceCommandRequest(UUID executionId, UUID projectId, UUID requestedBy, UUID deviceId,
                                       String commandKey, JsonNode input) {

    /** 校验任务投递不可缺少的定位字段。 */
    public TaskDeviceCommandRequest {
        if (executionId == null || projectId == null || requestedBy == null || deviceId == null
                || commandKey == null || commandKey.isBlank() || input == null || !input.isObject()) {
            throw new IllegalArgumentException("任务设备命令请求不完整");
        }
    }
}
