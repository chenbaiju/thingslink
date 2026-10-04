package com.things.link.telemetry.domain;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息日志键集分页查询条件。
 *
 * @param projectId 项目 ID
 * @param deviceId 可选设备 ID；为空时查询项目内全部设备
 * @param direction 可选消息方向
 * @param protocol 可选传输协议
 * @param from 可选开始时刻，包含
 * @param to 可选结束时刻，不包含
 * @param traceId 可选精确追踪 ID
 * @param cursor 可选不透明游标
 * @param limit 每页数量
 */
public record MessageLogQuery(UUID projectId, UUID deviceId, DeviceMessageLog.Direction direction,
                              TransportProtocol protocol, Instant from, Instant to,
                              String traceId, String cursor, int limit, String messageType) {

    /**
     * 兼容不带消息类型的调用方。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param direction 方向
     * @param protocol 协议
     * @param from 开始时刻
     * @param to 结束时刻
     * @param traceId 链路标识
     * @param cursor 游标
     * @param limit 每页数量
     */
    public MessageLogQuery(UUID projectId, UUID deviceId, DeviceMessageLog.Direction direction,
                           TransportProtocol protocol, Instant from, Instant to, String traceId, String cursor,
                           int limit) {
        this(projectId, deviceId, direction, protocol, from, to, traceId, cursor, limit, null);
    }
    /** HTTP 之外的调用也必须执行范围和时间边界校验。 */
    public MessageLogQuery {
        traceId = blankToNull(traceId);
        cursor = blankToNull(cursor);
        messageType = blankToNull(messageType);
        if (projectId == null || limit < 1 || limit > 200 || traceId != null && traceId.length() > 64
                || messageType != null && messageType.length() > 32
                || from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "消息日志查询条件不合法");
        }
    }

    /** @param value 可空文本 @return 去空白文本，空白转 null */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
