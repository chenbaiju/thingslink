package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 数据运行合同§3.5/3.6的只读告警页，排除规则、操作者、原始属性与个人已读事实。
 * @param items 当前一页过滤后事故
 * @param nextCursor 有下一页时的不透明签名游标，否则null
 * @param hasMore 是否存在下一页
 */
@Schema(name = "AlarmDeviceQueryResponse")
public record AlarmDeviceQueryResponse(
        @NotNull @ArraySchema(minItems = 0, maxItems = 50) List<Item> items,
        @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
    /** 固定当前页集合，不能把持久对象附加字段透传到响应。 */
    public AlarmDeviceQueryResponse {
        items = List.copyOf(items);
    }

    /** @param page 应用公开只读页 @return 显式HTTP投影 */
    public static AlarmDeviceQueryResponse from(CursorPage<AlarmDeviceQueryItem> page) {
        return new AlarmDeviceQueryResponse(page.items().stream().map(Item::from).toList(), page.nextCursor(), page.hasMore());
    }

    /**
     * 单条事故的封闭事实，不提供ACK/CLEAR写资格。
     * @param id 事故ID
     * @param deviceId 来源设备
     * @param alarmType 类型纯文本
     * @param severity 严重程度
     * @param conditionState 条件状态
     * @param ackState 确认状态
     * @param firstConditionAt 首次条件成立时间
     * @param activatedAt 激活时间，未激活可空
     * @param clearedAt 清除时间，可空
     * @param acknowledgedAt 确认时间，可空
     * @param lastReceivedAt 最近接收时间
     * @param version 事故乐观锁整数版本
     */
    @Schema(name = "AlarmDeviceQueryItemResponse")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String alarmType,
            @Schema(allowableValues = {"CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"}, requiredMode = Schema.RequiredMode.REQUIRED) String severity,
            @Schema(allowableValues = {"PENDING", "ACTIVE", "CLEARED"}, requiredMode = Schema.RequiredMode.REQUIRED) String conditionState,
            @Schema(allowableValues = {"UNACKNOWLEDGED", "ACKNOWLEDGED"}, requiredMode = Schema.RequiredMode.REQUIRED) String ackState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant firstConditionAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED) Instant activatedAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED) Instant clearedAt,
            @Schema(types = {"string", "null"}, format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED) Instant acknowledgedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant lastReceivedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version) {
        /** @param value 无敏感元数据的应用投影 @return 冻结十二字段 */
        static Item from(AlarmDeviceQueryItem value) {
            return new Item(value.id(), value.deviceId(), value.alarmType(), value.severity(), value.conditionState(), value.ackState(),
                    value.firstConditionAt(), value.activatedAt(), value.clearedAt(), value.acknowledgedAt(), value.lastReceivedAt(), value.version());
        }
    }
}
