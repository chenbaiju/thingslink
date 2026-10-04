package com.things.link.rule.application;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 手动执行场景命令；目标设备与输入载荷由服务端校验属主与边界后才进入规则消息。
 *
 * @param deviceId 一次执行的目标设备 ID，必填
 * @param payload 条件节点读取的 JSON 对象，可省略，默认空对象
 */
public record ExecuteSceneCommand(UUID deviceId, JsonNode payload) {
}
