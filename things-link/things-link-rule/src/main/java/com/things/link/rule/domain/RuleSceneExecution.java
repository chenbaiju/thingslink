package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 手动场景一次一键执行的封闭事实，兼 HTTP 幂等落点（见 ADR 0030）。
 *
 * <p>同步执行在单次事务内即达终态，故无进行中状态、无重试租约；DISPATCHED 只表示副作用已可靠写入
 * Outbox，不代表已送达。</p>
 *
 * @param id 执行 UUIDv7 主键，兼作构造规则消息的 messageId
 * @param tenantId 场景所属项目的 owner tenant ID
 * @param projectId 执行所属项目及 RLS 隔离轴
 * @param sceneId 被执行的场景定义 ID
 * @param sceneVersionId 首次执行绑定的不可变场景版本 ID，同一幂等键重试保持不变
 * @param idempotencyKey 客户端必填的 Idempotency-Key 头原值，唯一键仲裁并发与重试
 * @param requestDigest 请求体（deviceId + payload）的 SHA-256 小写十六进制摘要，识别同键不同内容
 * @param deviceId 服务端校验属主后的一次执行目标设备 ID
 * @param status 封闭终态
 * @param trigger 触发类型，固定为 MANUAL，由服务端写入
 * @param operatorAccountId 点击执行的控制台账号 ID，由服务端写入
 * @param traceId 本次请求的全链路追踪 ID
 * @param occurredAt 服务端受理执行请求的 UTC 时刻，兼作规则消息 occurredAt
 * @param createdAt 执行事实落库的 UTC 时刻
 * @param completedAt 同步执行进入终态的 UTC 时刻，等于创建时刻
 */
public record RuleSceneExecution(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID sceneId,
        UUID sceneVersionId,
        String idempotencyKey,
        String requestDigest,
        UUID deviceId,
        Status status,
        Trigger trigger,
        UUID operatorAccountId,
        String traceId,
        Instant occurredAt,
        Instant createdAt,
        Instant completedAt) {

    /** 执行封闭终态。 */
    public enum Status {
        /** 全部条件成立，动作意图已可靠写入 Outbox。 */
        DISPATCHED,
        /** 首个条件不成立，短路结束，未产生任何副作用。 */
        SKIPPED,
        /** 条件或动作求值 fail-closed（非法关系、未知节点或节点异常），未产生副作用。 */
        FAILED
    }

    /** 触发类型；S9-4 只支持手动。 */
    public enum Trigger {
        MANUAL
    }
}
