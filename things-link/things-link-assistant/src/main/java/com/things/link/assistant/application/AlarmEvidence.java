package com.things.link.assistant.application;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 告警域返回的有界事实页；当前模型仅用于访问复核，不证明历史事故来源。
 * @param projectId 当前受权项目
 * @param deviceId 当前受权设备
 * @param currentModelVersionId 本次检查的当前模型版本
 * @param collectedAt 本页取证完成时间，不是事故发生时间
 * @param sourceModelState 告警查询端口未提供事故模型版本，固定为未提供
 * @param items 最多五十条事故事实，空页不代表设备正常
 * @param nextCursor 当前账号、设备、模型及页大小绑定的下一游标
 * @param hasMore 是否存在后续页，不保证分页期间全量稳定快照
 * @param limit 本次固定页大小
 */
@Schema(name = "AssistantAlarmEvidence", description = "受权事故事实页；不含时间窗承诺、自由文本或模型分析")
public record AlarmEvidence(UUID projectId, UUID deviceId, UUID currentModelVersionId, Instant collectedAt,
        @Schema(allowableValues = {"NOT_PROVIDED"}) String sourceModelState,
        @ArraySchema(maxItems = 50, schema = @Schema(implementation = Item.class)) List<Item> items,
        @Schema(types = {"string", "null"}) String nextCursor,
        boolean hasMore, @Schema(minimum = "1", maximum = "50") int limit) {
    /** 保存不可变本页，不把空引用规整成成功空页。 */
    public AlarmEvidence { items = List.copyOf(items); }

    /**
     * 不含告警类型自由文本、规则、操作者及原始属性值的事故事实。
     * @param id 事故标识，仅作本项目内引用
     * @param severity 严重度机器枚举
     * @param conditionState 条件状态机器枚举
     * @param ackState 确认状态机器枚举
     * @param firstConditionAt 首次条件成立时间
     * @param activatedAt 激活时间，未激活时为空
     * @param clearedAt 清除时间，未清除时为空
     * @param acknowledgedAt 确认时间，未确认时为空
     * @param lastReceivedAt 最近接收时间
     * @param version 告警事实版本，不是物模型版本
     */
    @Schema(name = "AssistantAlarmEvidenceItem", description = "封闭枚举和时间事实；自由文本告警类型不返回")
    public record Item(UUID id,
            @Schema(allowableValues = {"CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"}) String severity,
            @Schema(allowableValues = {"PENDING", "ACTIVE", "CLEARED"}) String conditionState,
            @Schema(allowableValues = {"UNACKNOWLEDGED", "ACKNOWLEDGED"}) String ackState,
            Instant firstConditionAt,
            @Schema(types = {"string", "null"}, format = "date-time") Instant activatedAt,
            @Schema(types = {"string", "null"}, format = "date-time") Instant clearedAt,
            @Schema(types = {"string", "null"}, format = "date-time") Instant acknowledgedAt,
            Instant lastReceivedAt, @Schema(minimum = "0") int version) { }
}
