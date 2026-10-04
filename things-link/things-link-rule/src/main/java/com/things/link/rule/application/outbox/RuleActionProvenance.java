package com.things.link.rule.application.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 中性动作派发所需的受信执行身份与来源归属。
 *
 * <p>来源是互斥的规则组（{@code ruleId/ruleVersionId} + {@code messageId}）、场景组
 * （{@code sceneId/sceneVersionId/sceneExecutionId}），或自动化组（automationId/automationVersionId/automationExecutionId），按ADR0152严格互斥。
 * 消息规则的 {@code messageId} 是上行消息 ID，场景及自动化的 {@code messageId} 等于各自执行ID。</p>
 *
 * @param tenantId 项目 owner tenant
 * @param projectId 项目隔离轴
 * @param deviceId 已确权目标设备
 * @param traceId 全链路追踪标识
 * @param occurredAt 设备事件时间（手动场景为服务端受理时刻）
 * @param receivedAt 平台接收/入队时刻（手动场景等于受理时刻）
 * @param messageId 引擎与告警动作使用的稳定消息 ID
 * @param requestedBy 设备动作 requested_by 账号 ID（规则版本创建者或场景操作者）
 * @param ruleId 规则来源定义 ID；场景来源为空
 * @param ruleVersionId 规则来源不可变版本 ID；场景来源为空
 * @param sceneId 场景来源定义 ID；规则来源为空
 * @param sceneVersionId 场景来源不可变版本 ID；规则来源为空
 * @param sceneExecutionId 场景来源执行事实 ID；其他来源为空
 * @param automationId 自动化定义ID；其他来源为空
 * @param automationVersionId 自动化冻结版本ID；其他来源为空
 * @param automationExecutionId 自动化执行ID；其他来源为空
 */
public record RuleActionProvenance(
        UUID tenantId,
        UUID projectId,
        UUID deviceId,
        String traceId,
        Instant occurredAt,
        Instant receivedAt,
        UUID messageId,
        UUID requestedBy,
        UUID ruleId,
        UUID ruleVersionId,
        UUID sceneId,
        UUID sceneVersionId,
        UUID sceneExecutionId, UUID automationId, UUID automationVersionId, UUID automationExecutionId) {

    /** 旧规则/场景的源码兼容入口。 */
    public RuleActionProvenance(UUID tenantId, UUID projectId, UUID deviceId, String traceId,
            Instant occurredAt, Instant receivedAt, UUID messageId, UUID requestedBy,
            UUID ruleId, UUID ruleVersionId, UUID sceneId, UUID sceneVersionId, UUID sceneExecutionId) {
        this(tenantId, projectId, deviceId, traceId, occurredAt, receivedAt, messageId, requestedBy,
                ruleId, ruleVersionId, sceneId, sceneVersionId, sceneExecutionId, null, null, null);
    }

    /** 自动化使用冻结执行身份，保留源事件时间。 */
    public static RuleActionProvenance automation(UUID tenantId, UUID projectId, UUID deviceId, String traceId,
            Instant occurredAt, Instant acceptedAt, UUID requestedBy, UUID automationId,
            UUID automationVersionId, UUID executionId) {
        return new RuleActionProvenance(tenantId, projectId, deviceId, traceId, occurredAt, acceptedAt,
                executionId, requestedBy, null, null, null, null, null,
                automationId, automationVersionId, executionId);
    }

    /** 拒绝缺失身份或来源归属不满足互斥，避免投递事实在入库时才发现来源不明。 */
    public RuleActionProvenance {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(deviceId, "deviceId 不能为空");
        Objects.requireNonNull(traceId, "traceId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(receivedAt, "receivedAt 不能为空");
        Objects.requireNonNull(messageId, "messageId 不能为空");
        Objects.requireNonNull(requestedBy, "requestedBy 不能为空");
        boolean ruleSource = ruleId != null && ruleVersionId != null
                && sceneId == null && sceneVersionId == null && sceneExecutionId == null
                && automationId == null && automationVersionId == null && automationExecutionId == null;
        boolean sceneSource = sceneId != null && sceneVersionId != null && sceneExecutionId != null
                && ruleId == null && ruleVersionId == null
                && automationId == null && automationVersionId == null && automationExecutionId == null;
        boolean automationSource = automationId != null && automationVersionId != null && automationExecutionId != null
                && ruleId == null && ruleVersionId == null
                && sceneId == null && sceneVersionId == null && sceneExecutionId == null;
        if (!ruleSource && !sceneSource && !automationSource) {
            throw new IllegalArgumentException("动作来源必须是完整互斥的规则、场景或自动化组");
        }
        if ((sceneSource && !messageId.equals(sceneExecutionId))
                || (automationSource && !messageId.equals(automationExecutionId))) {
            throw new IllegalArgumentException("执行来源必须使用稳定执行ID作为消息ID");
        }
    }

    /** @return 消息规则来源；{@code receivedAt} 为规则执行入队时刻 */
    public static RuleActionProvenance messageRule(
            UUID tenantId, UUID projectId, UUID deviceId, String traceId,
            Instant occurredAt, Instant receivedAt, UUID messageId, UUID requestedBy,
            UUID ruleId, UUID ruleVersionId) {
        return new RuleActionProvenance(tenantId, projectId, deviceId, traceId, occurredAt, receivedAt,
                messageId, requestedBy, ruleId, ruleVersionId, null, null, null);
    }

    /** @return 手动场景来源；{@code messageId} 与 {@code receivedAt} 都取执行受理时刻语义 */
    public static RuleActionProvenance scene(
            UUID tenantId, UUID projectId, UUID deviceId, String traceId, Instant occurredAt,
            UUID requestedBy, UUID sceneId, UUID sceneVersionId, UUID sceneExecutionId) {
        return new RuleActionProvenance(tenantId, projectId, deviceId, traceId, occurredAt, occurredAt,
                sceneExecutionId, requestedBy, null, null, sceneId, sceneVersionId, sceneExecutionId);
    }

    /** @return 来源是否消息规则 */
    public boolean isMessageRule() {
        return ruleId != null;
    }
}
