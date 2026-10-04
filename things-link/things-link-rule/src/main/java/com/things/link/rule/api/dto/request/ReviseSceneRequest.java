package com.things.link.rule.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改手动场景 HTTP 写入契约；追加新不可变版本，既有版本绝不覆盖。
 *
 * @param name 项目内唯一名称
 * @param description 用途说明
 * @param expectedVersion 定义乐观锁版本
 * @param conditions 有序条件节点
 * @param actions 有序动作节点
 */
public record ReviseSceneRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @Min(1) long expectedVersion,
        List<@Valid SceneConditionRequest> conditions,
        @NotEmpty List<@Valid SceneActionRequest> actions) {
}
