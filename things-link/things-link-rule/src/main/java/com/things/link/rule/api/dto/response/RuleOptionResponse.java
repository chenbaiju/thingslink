package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.RuleOption;

import java.util.UUID;

/**
 * 执行记录筛选下拉框的只读选项响应。
 *
 * @param id 定义 ID
 * @param name 查询时刻的当前名称
 */
public record RuleOptionResponse(UUID id, String name) {

    /** @return 领域选项到 API 响应 */
    public static RuleOptionResponse from(RuleOption option) {
        return new RuleOptionResponse(option.id(), option.name());
    }
}
