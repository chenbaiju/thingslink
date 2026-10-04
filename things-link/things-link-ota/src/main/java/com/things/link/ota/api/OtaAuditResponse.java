package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.support.audit.AuditQueryRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * OTA审计时间线公开白名单。
 *
 * <p>只暴露 {@code sys_audit_log} 里已经存在的列，不追加租户、项目等调用方本就知道
 * 的上下文，也不补查业务正文：审计接口是排障入口，它显示的必须是当时真正落库的
 * 事实，而不是读取时重新推导出来的结论。
 *
 * @param id             审计 ID
 * @param actorAccountId 行为人账号，系统任务为空
 * @param targetType     目标类型
 * @param targetId       目标 ID，可为空
 * @param action         稳定动作编码
 * @param traceId        链路 ID，可为空
 * @param details        结构化详情，原样来自 jsonb
 * @param createdAt      记录时刻
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "OtaAuditResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record OtaAuditResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") UUID actorAccountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") UUID targetId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) String traceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, Object> details,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    /** 详情对象的 JSON 类型，只解析一次，避免每行各自反射推断。 */
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() { };

    /**
     * 从仓储只读事实投影 HTTP 响应。
     *
     * @param value        审计只读事实
     * @param objectMapper jsonb 文本解析器
     * @return 公开响应
     */
    public static OtaAuditResponse from(AuditQueryRepository.Record value, ObjectMapper objectMapper) {
        return new OtaAuditResponse(value.id(), value.actorAccountId(), value.targetType(), value.targetId(),
                value.action(), value.traceId(), details(value.details(), objectMapper), value.createdAt());
    }

    /**
     * 解析 raw jsonb 文本。
     *
     * <p>解析失败时回退为空对象而不是抛异常：审计读取本身就是排障入口，
     * 一条历史脏数据不该让整条时间线变成 500 —— 那会把「某条记录格式异常」
     * 放大成「整个项目查不到审计」。
     *
     * @param raw          原始 jsonb 文本，可为空
     * @param objectMapper JSON 解析器
     * @return 结构化详情；空白或无法解析时为空对象
     */
    private static Map<String, Object> details(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            // Jackson 3 的 JacksonException 继承 RuntimeException，这里按运行时异常统一兜底。
            Map<String, Object> parsed = objectMapper.readValue(raw, DETAILS);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }
}
