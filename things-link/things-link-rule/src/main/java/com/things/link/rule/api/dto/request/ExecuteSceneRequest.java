package com.things.link.rule.api.dto.request;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 一键执行场景 HTTP 写入契约；设备必填，载荷可省略默认空对象，由服务端校验属主与大小边界。
 *
 * @param deviceId 一次执行的目标设备 ID
 * @param payload 条件节点读取的 JSON 对象
 */
public record ExecuteSceneRequest(
        @NotNull UUID deviceId,
        JsonNode payload) {
}
