package com.things.link.rule.application.engine;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 节点请求外部动作时返回的纯数据意图；真正副作用必须由后续 S9 Outbox 边界执行。
 *
 * @param type 固定意图类型
 * @param payload 意图参数快照
 */
public record RuleSideEffectIntent(String type, JsonNode payload) {

    /** 防御性复制参数，禁止执行器之外的代码改变已经产生的意图。 */
    public RuleSideEffectIntent {
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(payload, "payload 不能为空");
        payload = payload.deepCopy();
    }

    /** 每次读取返回副本，保持意图事实不可变。 */
    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
