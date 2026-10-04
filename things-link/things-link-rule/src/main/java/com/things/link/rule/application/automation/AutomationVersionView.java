package com.things.link.rule.application.automation;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
/** 仅管理角色可读的不可变版本投影。 */
public record AutomationVersionView(UUID id,long versionNumber,String triggerType,JsonNode triggerConfig,
        JsonNode conditions,JsonNode actions,UUID createdBy,Instant createdAt) {}
