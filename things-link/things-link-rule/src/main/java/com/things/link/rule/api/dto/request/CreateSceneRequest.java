package com.things.link.rule.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建手动场景 HTTP 写入契约；条件可空（表示“总是执行”），动作必须至少一项。
 *
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param conditions 有序条件节点（ALL_OF 短路求值）
 * @param actions 有序动作节点
 */
public record CreateSceneRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        List<@jakarta.validation.Valid SceneConditionRequest> conditions,
        @NotEmpty List<@jakarta.validation.Valid SceneActionRequest> actions) {
}
