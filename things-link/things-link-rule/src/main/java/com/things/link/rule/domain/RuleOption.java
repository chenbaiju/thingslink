package com.things.link.rule.domain;

import java.util.UUID;

/**
 * 执行记录筛选下拉框的轻量只读选项：已经产生过执行事实的规则或场景的 {@code id + name}。
 *
 * <p>故意不依赖消息规则/场景管理控制器，独立从执行事实反查当前定义名称，作为日志筛选的专用数据源。</p>
 *
 * @param id 定义 ID
 * @param name 查询时刻的当前名称
 */
public record RuleOption(UUID id, String name) {
}
