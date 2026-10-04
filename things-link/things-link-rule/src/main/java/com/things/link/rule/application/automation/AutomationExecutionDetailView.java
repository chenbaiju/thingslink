package com.things.link.rule.application.automation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** 运行详情只有状态摘要；不返回通知收件人、URL或正文。 */
public record AutomationExecutionDetailView(AutomationExecutionView summary,List<Attempt> attempts,
        List<Notification> notifications,List<DeviceAction> deviceActions) {
    public record Attempt(int attemptNumber,Instant startedAt,Instant finishedAt,String outcome,String reasonCode) {}
    public record Notification(String channel,String status,int attemptCount,String lastErrorCode,Instant deliveredAt) {}
    public record DeviceAction(UUID deviceId,String operationType,UUID commandId,String status,String failureCode,Instant completedAt) {}
}
