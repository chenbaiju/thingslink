package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.RuleSceneExecutionDetail;

import java.util.List;

/**
 * 一次手动场景执行的详情响应：执行事实本体 + 通知与设备动作投递状态摘要。
 *
 * @param execution 执行事实投影
 * @param notifications 已渲染通知投递事实
 * @param deviceActions 设备命令/属性设置投递事实
 */
public record RuleSceneExecutionDetailResponse(
        RuleSceneExecutionResponse execution,
        List<NotificationDeliverySummaryResponse> notifications,
        List<DeviceActionDeliverySummaryResponse> deviceActions) {

    /** @return 领域详情到 API 响应 */
    public static RuleSceneExecutionDetailResponse from(RuleSceneExecutionDetail detail) {
        return new RuleSceneExecutionDetailResponse(
                RuleSceneExecutionResponse.from(detail.summary()),
                detail.notifications().stream().map(NotificationDeliverySummaryResponse::from).toList(),
                detail.deviceActions().stream().map(DeviceActionDeliverySummaryResponse::from).toList());
    }
}
