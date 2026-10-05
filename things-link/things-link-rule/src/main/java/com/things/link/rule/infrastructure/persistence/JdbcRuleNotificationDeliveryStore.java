package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.RuleNotificationDeliveryStore;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通过 SECURITY DEFINER 函数维护规则通知状态机，既保留项目 RLS，又支持无请求上下文的 Kafka/调度线程。
 *
 * <p>所有写操作都同时校验 {@code project_id/id/status/attempt_no}；即使伪造其他项目的 deliveryId，
 * 也不能读取快照或改变终态。跨项目扫描只返回发送所需的冻结字段，不暴露规则源码或其他项目配置。</p>
 */
@Repository
public class JdbcRuleNotificationDeliveryStore implements RuleNotificationDeliveryStore {

    /** 规则模块 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已配置应用角色的数据源访问器 */
    public JdbcRuleNotificationDeliveryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Acceptance accept(RuleNotificationDeliveryRequest request) {
        String result = jdbcTemplate.queryForObject("""
                SELECT rule_notification_delivery_acceptance_v2(
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, String.class,
                request.eventId(), request.tenantId(), request.projectId(),
                request.ruleId(), request.ruleVersionId(), request.messageId(),
                request.sceneId(), request.sceneVersionId(), request.sceneExecutionId(),
                request.deviceId(), request.channel(), request.recipient(), request.subject(),
                request.body(), request.traceId(), request.attemptNo(), Timestamp.from(request.enqueuedAt()),
                request.automationId(), request.automationVersionId(), request.automationExecutionId());
        return Acceptance.valueOf(result);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean startClaimed(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            UUID dispatchToken,
            Instant startedAt,
            Instant recoveryAt) {
        return changed("SELECT rule_notification_delivery_start_claimed(?,?,?,?,?,?)",
                projectId, deliveryId, attemptNo, dispatchToken,
                Timestamp.from(startedAt), Timestamp.from(recoveryAt));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public DispatchClaim claimDispatches(int limit, Duration lease) {
        UUID leaseToken = Uuid7.generate();
        List<DispatchCandidate> deliveries = jdbcTemplate.query(
                "SELECT * FROM claim_rule_notification_dispatches(?,?,?)",
                (rs, row) -> new DispatchCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("rule_version_id", UUID.class),
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("scene_id", UUID.class),
                        rs.getObject("scene_version_id", UUID.class),
                        rs.getObject("scene_execution_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getString("trace_id"),
                        rs.getInt("attempt_no"),
                        rs.getTimestamp("enqueued_at").toInstant(),
                        rs.getObject("automation_id", UUID.class), rs.getObject("automation_version_id", UUID.class),
                        rs.getObject("automation_execution_id", UUID.class)),
                leaseToken,
                limit,
                Math.toIntExact(lease.toSeconds()));
        return new DispatchClaim(leaseToken, List.copyOf(deliveries));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public DispatchAges dispatchAges() {
        return jdbcTemplate.queryForObject(
                "SELECT queued_seconds,sending_seconds FROM rule_notification_dispatch_ages()",
                (result, row) -> new DispatchAges(
                        Duration.ofSeconds(result.getLong("queued_seconds")),
                        Duration.ofSeconds(result.getLong("sending_seconds"))));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean releaseDispatch(
            UUID projectId, UUID deliveryId, UUID dispatchToken, Duration retryDelay) {
        return changed("SELECT release_rule_notification_dispatch(?,?,?,?)",
                projectId, deliveryId, dispatchToken, Math.toIntExact(retryDelay.toSeconds()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markDelivered(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String providerMessageId,
            Instant deliveredAt) {
        return changed("SELECT rule_notification_delivery_succeed(?, ?, ?, ?, ?)",
                projectId, deliveryId, attemptNo, providerMessageId, Timestamp.from(deliveredAt));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markRetry(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            Instant nextAttemptAt,
            String errorCode,
            Instant updatedAt) {
        return changed("SELECT rule_notification_delivery_retry(?, ?, ?, ?, ?, ?)",
                projectId, deliveryId, attemptNo, Timestamp.from(nextAttemptAt), errorCode,
                Timestamp.from(updatedAt));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean markDeadLetter(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String errorCode,
            Instant terminalAt) {
        return changed("SELECT rule_notification_delivery_dead_letter(?, ?, ?, ?, ?)",
                projectId, deliveryId, attemptNo, errorCode, Timestamp.from(terminalAt));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RetryClaim claimDueRetries(int limit, Duration lease) {
        UUID leaseToken = Uuid7.generate();
        List<RetryCandidate> deliveries = jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, rule_id, rule_version_id, message_id,
                       scene_id, scene_version_id, scene_execution_id, device_id,
                       channel, recipient, subject, body, trace_id, next_attempt_no,
                       automation_id, automation_version_id, automation_execution_id
                  FROM claim_rule_notification_retries(?, ?, ?)
                """, (rs, rowNum) -> new RetryCandidate(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                rs.getObject("rule_version_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("scene_id", UUID.class),
                rs.getObject("scene_version_id", UUID.class),
                rs.getObject("scene_execution_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("channel"),
                rs.getString("recipient"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("trace_id"),
                rs.getInt("next_attempt_no"), rs.getObject("automation_id", UUID.class),
                rs.getObject("automation_version_id", UUID.class), rs.getObject("automation_execution_id", UUID.class)),
                leaseToken, limit, Math.toIntExact(lease.toSeconds()));
        return new RetryClaim(leaseToken, deliveries);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean matchesClaimedRetry(RetryCandidate candidate, UUID leaseToken, Instant now) {
        // APP仅有SELECT权限；不以FOR UPDATE扩大表权限。不可变字段先核验，最终写函数再仲裁有效租约。
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM rule_notification_delivery
                 WHERE tenant_id=? AND project_id=? AND id=? AND retry_lease_token=?
                   AND retry_leased_until > clock_timestamp() AND next_attempt_at <= ?
                   AND ((status='RETRY_SCHEDULED' AND attempt_count < max_attempts AND attempt_count+1=?)
                     OR (status='SENDING' AND attempt_count=?))
                   AND rule_id IS NOT DISTINCT FROM ?::uuid AND rule_version_id IS NOT DISTINCT FROM ?::uuid
                   AND message_id IS NOT DISTINCT FROM ?::uuid AND scene_id IS NOT DISTINCT FROM ?::uuid
                   AND scene_version_id IS NOT DISTINCT FROM ?::uuid AND scene_execution_id IS NOT DISTINCT FROM ?::uuid
                   AND automation_id IS NOT DISTINCT FROM ?::uuid
                   AND automation_version_id IS NOT DISTINCT FROM ?::uuid
                   AND automation_execution_id IS NOT DISTINCT FROM ?::uuid
                   AND device_id=? AND channel=? AND recipient=? AND subject=? AND body=? AND trace_id=?)
                """, Boolean.class, candidate.tenantId(), candidate.projectId(), candidate.id(), leaseToken,
                Timestamp.from(now), candidate.nextAttemptNo(), candidate.nextAttemptNo(), candidate.ruleId(),
                candidate.ruleVersionId(), candidate.messageId(), candidate.sceneId(), candidate.sceneVersionId(),
                candidate.sceneExecutionId(), candidate.automationId(), candidate.automationVersionId(),
                candidate.automationExecutionId(), candidate.deviceId(), candidate.channel(), candidate.recipient(),
                candidate.subject(), candidate.body(), candidate.traceId()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean stopClaimedRetryForProjectFreeze(UUID tenantId, UUID projectId, UUID deliveryId,
            UUID leaseToken, int expectedAttemptNo, Instant now) {
        return changed("SELECT rule_notification_stop_frozen_retry(?,?,?,?,?,?)", tenantId, projectId,
                deliveryId, leaseToken, expectedAttemptNo, Timestamp.from(now));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean requeueClaimedRetry(
            UUID projectId,
            UUID deliveryId,
            UUID leaseToken,
            UUID outboxEventId,
            int nextAttemptNo,
            Instant updatedAt) {
        return changed("SELECT rule_notification_delivery_requeue(?, ?, ?, ?, ?, ?)",
                projectId, deliveryId, leaseToken, outboxEventId, nextAttemptNo,
                Timestamp.from(updatedAt));
    }

    /** @return 单行布尔数据库函数是否成功推进 CAS */
    private boolean changed(String sql, Object... arguments) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, arguments));
    }

}
