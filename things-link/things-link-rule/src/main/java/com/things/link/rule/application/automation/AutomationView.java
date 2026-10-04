package com.things.link.rule.application.automation;
import java.time.Instant;
import java.util.UUID;
/** 自动化目录投影，不含冻结配置或责任身份。 */
public record AutomationView(UUID id,String name,String description,String status,UUID activeVersionId,
        String activeTriggerType,Instant nextFireAt,String scheduleTimezone,long version,Instant createdAt,Instant updatedAt) {}
