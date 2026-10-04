package com.things.link.support.audit;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** ADR0075 审计事实的流式读取导出端口。 */
public interface AuditExportSource {

    /**
     * 按稳定主键顺序流出项目审计；details只按既有持久事实输出，不补查业务正文。
     * @param tenantId 项目真实归属租户
     * @param projectId 项目 ID
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamAuditLogs(UUID tenantId, UUID projectId, AuditSink sink);

    /**
     * {@code audit-logs.jsonl} 白名单事实。
     * @param id 审计 ID
     * @param actorAccountId 行为账号
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param action 动作编码
     * @param traceId 链路 ID
     * @param details 已写入的脱敏详情
     * @param createdAt 记录时刻
     */
    record AuditExportRow(UUID id, UUID actorAccountId, String targetType, UUID targetId,
                          String action, String traceId, JsonNode details, Instant createdAt) {
    }

    /** 单行审计回调。 */
    @FunctionalInterface
    interface AuditSink {
        /** @param entry 当前审计事实 */
        void accept(AuditExportRow entry);
    }
}
