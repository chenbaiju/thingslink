package com.things.link.support.audit;

import java.util.Map;
import java.util.UUID;

/**
 * 一条待写入的审计事件。
 *
 * <p>这是 support 模块暴露给业务模块的最小契约：业务模块只声明“谁、在哪个范围、
 * 对什么目标、做了什么”，持久化细节由 {@link AuditLogService} 负责。
 *
 * @param tenantId       租户 ID；账号级动作可为 null
 * @param projectId      项目 ID；项目级动作必须填
 * @param actorAccountId 行为人账号 ID；系统任务可为 null
 * @param targetType     目标类型，例如 {@code project_member}
 * @param targetId       目标 ID；没有稳定目标 ID 时可为 null
 * @param action         稳定动作编码
 * @param details        结构化详情，禁止放口令、令牌、密钥等敏感值
 */
public record AuditLogEntry(
        UUID tenantId,
        UUID projectId,
        UUID actorAccountId,
        String targetType,
        UUID targetId,
        String action,
        Map<String, ?> details) {
}
