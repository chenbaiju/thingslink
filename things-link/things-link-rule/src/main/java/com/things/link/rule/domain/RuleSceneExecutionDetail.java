package com.things.link.rule.domain;

import java.util.List;

/**
 * 一次手动场景执行的详情：执行事实本体 + 已产生的动作关联投递事实。
 *
 * <p>详情只在执行事实之外追加通知与设备动作两类投递状态摘要，不展开条件/动作正文、payload 或异常正文。</p>
 *
 * @param summary 执行事实投影
 * @param notifications 该次执行的已渲染通知投递事实（按创建时刻升序）
 * @param deviceActions 该次执行的设备命令/属性设置投递事实（按创建时刻升序）
 */
public record RuleSceneExecutionDetail(
        RuleSceneExecutionSummary summary,
        List<NotificationDeliverySummary> notifications,
        List<DeviceActionDeliverySummary> deviceActions) {
}
