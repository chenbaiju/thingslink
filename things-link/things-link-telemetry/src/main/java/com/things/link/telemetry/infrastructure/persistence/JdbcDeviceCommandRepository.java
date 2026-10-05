package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandAttempt;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 保存命令事实并用前置状态条件实现数据库 CAS。 */
@Repository
public class JdbcDeviceCommandRepository implements DeviceCommandRepository {
    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcDeviceCommandRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean create(DeviceCommand value) {
        return jdbcTemplate.update("""
                INSERT INTO ts_device_command
                    (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                     operation_type, command_key, input_schema, output_schema, request_payload, response_payload, status,
                     idempotency_key, requested_by, app_user_id, timeout_seconds, attempt_count, max_attempts,
                     next_attempt_at, deadline_at, failure_code, failure_message, trace_id,
                     accepted_at, dispatched_at, acknowledged_at, completed_at, updated_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::jsonb,
                        ?::jsonb, ?::jsonb, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, idempotency_key) DO NOTHING
                """, value.id(), value.tenantId(), value.projectId(), value.targetDeviceId(),
                value.connectionDeviceId(), value.commandDefinitionId(), value.operationType().name(), value.commandKey(), value.inputSchema(),
                value.outputSchema(), value.requestJson(), value.responseJson(), value.status().name(),
                value.idempotencyKey(), value.requestedBy(), value.appUserId(), value.timeoutSeconds(), value.attemptCount(),
                value.maxAttempts(), time(value.nextAttemptAt()), time(value.deadlineAt()), value.failureCode(),
                value.failureMessage(), value.traceId(), time(value.acceptedAt()), time(value.dispatchedAt()),
                time(value.acknowledgedAt()), time(value.completedAt()), time(value.updatedAt())) == 1;
    }

    @Override public boolean createPublic(DeviceCommand value, UUID keyId) {
        return jdbcTemplate.update("""
                INSERT INTO ts_device_command
                    (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                     operation_type, command_key, input_schema, output_schema, request_payload, response_payload, status,
                     idempotency_key, requested_by, app_user_id, timeout_seconds, attempt_count, max_attempts,
                     next_attempt_at, deadline_at, failure_code, failure_message, trace_id,
                     accepted_at, dispatched_at, acknowledged_at, completed_at, updated_at, integration_key_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::jsonb,
                        ?::jsonb, ?::jsonb, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
                ON CONFLICT (project_id, idempotency_key) DO NOTHING
                """, value.id(), value.tenantId(), value.projectId(), value.targetDeviceId(),
                value.connectionDeviceId(), value.commandDefinitionId(), value.operationType().name(), value.commandKey(), value.inputSchema(),
                value.outputSchema(), value.requestJson(), value.responseJson(), value.status().name(),
                value.idempotencyKey(), value.requestedBy(), value.appUserId(), value.timeoutSeconds(), value.attemptCount(),
                value.maxAttempts(), time(value.nextAttemptAt()), time(value.deadlineAt()), value.failureCode(),
                value.failureMessage(), value.traceId(), time(value.acceptedAt()), time(value.dispatchedAt()),
                time(value.acknowledgedAt()), time(value.completedAt()), time(value.updatedAt()), keyId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean hasPublicOrigin(UUID projectId, UUID commandId, UUID keyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM ts_device_command WHERE project_id=? AND id=? AND integration_key_id=?)",
            Boolean.class,projectId,commandId,keyId));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void createAttempt(DeviceCommandAttempt value) {
        // ADR0070：先锁父命令并核当前计数；失败必须回滚，禁止先插attempt留下孤立事实。
        int parent = jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET attempt_count = ?, deadline_at = ?, next_attempt_at = ?,
                       retry_token = NULL, retry_leased_until = NULL, updated_at = now()
                 WHERE tenant_id = ? AND project_id = ? AND id = ? AND attempt_count = ?
                   AND status = 'ACCEPTED' AND attempt_count < max_attempts
                """, value.attemptNo(), time(value.deadlineAt()), time(value.deadlineAt()),
                value.tenantId(), value.projectId(), value.commandId(), value.attemptNo() - 1);
        requireChanged(parent, "创建命令尝试的父状态CAS失败");
        int attempt = jdbcTemplate.update("""
                INSERT INTO ts_device_command_attempt
                    (id, tenant_id, project_id, command_id, attempt_no, outbox_event_id,
                     connection_device_id, topic, status, deadline_at, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.commandId(), value.attemptNo(),
                value.outboxEventId(), value.connectionDeviceId(), value.topic(), value.status().name(),
                time(value.deadlineAt()), time(value.createdAt()));
        requireChanged(attempt, "创建命令尝试失败");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommand> findByIdempotencyKey(UUID projectId, String key) {
        return jdbcTemplate.query(select() + " WHERE project_id = ? AND idempotency_key = ?",
                this::mapCommand, projectId, key).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommand> findById(UUID projectId, UUID deviceId, UUID commandId) {
        return jdbcTemplate.query(select() + " WHERE project_id = ? AND target_device_id = ? AND id = ?",
                this::mapCommand, projectId, deviceId, commandId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommand> findByCommandId(UUID projectId, UUID commandId) {
        return jdbcTemplate.query(select() + " WHERE project_id = ? AND id = ?",
                this::mapCommand, projectId, commandId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<DeviceCommand> lockByIdentity(UUID tenantId, UUID projectId, UUID commandId) {
        // 所有新工作先取得project许可，再以完整可信三元组锁父命令；等待后读取当前已提交事实。
        return jdbcTemplate.query(select() + " WHERE tenant_id = ? AND project_id = ? AND id = ? FOR UPDATE",
                this::mapCommand, tenantId, projectId, commandId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<DeviceCommandAttempt> findAttempts(UUID projectId, UUID commandId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, command_id, attempt_no, outbox_event_id,
                       connection_device_id, topic, status, deadline_at, created_at
                  FROM ts_device_command_attempt
                 WHERE project_id = ? AND command_id = ? ORDER BY attempt_no
                """, this::mapAttempt, projectId, commandId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markDispatched(UUID projectId, UUID commandId, int attemptNo, Instant publishedAt) {
        int attempt = jdbcTemplate.update("""
                UPDATE ts_device_command_attempt
                   SET status = 'PUBLISHED', published_at = ?, updated_at = now()
                 WHERE project_id = ? AND command_id = ? AND attempt_no = ? AND status = 'PENDING'
                """, time(publishedAt), projectId, commandId, attemptNo);
        if (attempt == 0) return false;
        int parent = jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET status = 'DISPATCHED', dispatched_at = ?, retry_token = NULL,
                       retry_leased_until = NULL, updated_at = now()
                 WHERE project_id = ? AND id = ? AND status = 'ACCEPTED' AND attempt_count = ?
                """, time(publishedAt), projectId, commandId, attemptNo);
        requireChanged(parent, "已更新派发尝试但父命令CAS失败");
        return true;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean markAttemptDispatchFailed(UUID projectId, UUID commandId, int attemptNo,
                                                       String failureCode, String failureMessage, Instant at) {
        return jdbcTemplate.update("""
                UPDATE ts_device_command_attempt
                   SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?, updated_at = now()
                 WHERE project_id = ? AND command_id = ? AND attempt_no = ? AND status = 'PENDING'
                """, failureCode, failureMessage, time(at), projectId, commandId, attemptNo) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean terminalDispatchRetryExhausted(UUID projectId, UUID commandId, int attemptNo, Instant at) {
        return jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET status = 'TIMED_OUT', failure_code = 'DISPATCH_RETRY_EXHAUSTED',
                       failure_message = '命令派发尝试耗尽且未取得交付回执', completed_at = ?,
                       deadline_at = NULL, next_attempt_at = NULL, retry_token = NULL,
                       retry_leased_until = NULL, updated_at = now()
                 WHERE project_id = ? AND id = ? AND attempt_count = ? AND status = 'ACCEPTED'
                """, time(at), projectId, commandId, attemptNo) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean scheduleDispatchRetry(UUID projectId, UUID commandId, int attemptNo, Instant nextAttemptAt) {
        return jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET deadline_at = NULL, next_attempt_at = ?, retry_token = NULL,
                       retry_leased_until = NULL, updated_at = now()
                 WHERE project_id = ? AND id = ? AND attempt_count = ? AND status = 'ACCEPTED'
                """, time(nextAttemptAt), projectId, commandId, attemptNo) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean applyReply(UUID projectId, UUID commandId, UUID connectionDeviceId,
                                       UUID replyMessageId, String replyStatus, String responseJson,
                                       String errorCode, String errorMessage, Instant at) {
        String attemptStatus = switch (replyStatus) {
            case "ACK" -> "ACKNOWLEDGED";
            case "SUCCESS" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            default -> throw new IllegalArgumentException("未知设备命令回复状态");
        };
        int attempt = jdbcTemplate.update("""
                UPDATE ts_device_command_attempt
                   SET status = ?, reply_message_id = ?::uuid, response_payload = ?::jsonb,
                       error_code = ?, error_message = ?,
                       acknowledged_at = CASE WHEN ? = 'ACKNOWLEDGED' THEN ? ELSE acknowledged_at END,
                       completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN ? ELSE completed_at END,
                       updated_at = now()
                 WHERE project_id = ? AND command_id = ? AND connection_device_id = ?
                   AND attempt_no = (SELECT attempt_count FROM ts_device_command WHERE id = ?)
                   -- 设备回执可能在 EMQX publish HTTP 返回、consumer 提交 markDispatched 之前到达；
                   -- ACK/终态是比管理 API 响应更强的已投递证据，必须允许它抢先收敛 PENDING。
                   AND status IN ('PENDING', 'PUBLISHED', 'ACKNOWLEDGED')
                """, attemptStatus, replyMessageId, responseJson, errorCode, errorMessage,
                attemptStatus, time(at), attemptStatus, time(at), projectId, commandId, connectionDeviceId, commandId);
        if (attempt == 0) {
            // 领取形态没有推送尝试行：回复必须落在那条领取事实上，否则领取到的命令会收不到结果。
            // 判据与推送一致（当前投递序号 + 连接设备 + 未终态），因此两种形态共用同一套终态语义。
            attempt = jdbcTemplate.update("""
                    UPDATE ts_device_command_claim
                       SET status = 'REPLIED', reply_message_id = ?::uuid, completed_at = ?,
                           updated_at = now()
                     WHERE project_id = ? AND command_id = ? AND connection_device_id = ?
                       AND attempt_no = (SELECT attempt_count FROM ts_device_command WHERE id = ?)
                       AND status = 'CLAIMED'
                    """, replyMessageId, time(at), projectId, commandId, connectionDeviceId, commandId);
            if (attempt == 0) return false;
        }
        int parent = jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET status = ?, response_payload = ?::jsonb, failure_code = ?, failure_message = ?,
                       acknowledged_at = CASE WHEN ? = 'ACKNOWLEDGED' THEN ? ELSE acknowledged_at END,
                       completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN ? ELSE completed_at END,
                       next_attempt_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN NULL ELSE next_attempt_at END,
                       deadline_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN NULL ELSE deadline_at END,
                       retry_token = NULL, retry_leased_until = NULL, updated_at = now()
                 WHERE project_id = ? AND id = ? AND connection_device_id = ?
                   AND status IN ('ACCEPTED', 'DISPATCHED', 'ACKNOWLEDGED')
                """, attemptStatus, responseJson, errorCode, errorMessage,
                attemptStatus, time(at), attemptStatus, time(at), attemptStatus, attemptStatus,
                projectId, commandId, connectionDeviceId);
        requireChanged(parent, "已更新回复尝试但父命令CAS失败");
        return true;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long countPending(UUID projectId, UUID deviceId) {
        Long pending = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ts_device_command
                 WHERE project_id = ? AND target_device_id = ?
                   AND status IN ('ACCEPTED', 'DISPATCHED', 'ACKNOWLEDGED')
                """, Long.class, projectId, deviceId);
        return pending == null ? 0L : pending;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int accelerateOfflinePending(UUID tenantId, UUID projectId, UUID deviceId) {
        return jdbcTemplate.update("""
                UPDATE ts_device_command command
                   SET next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
                 WHERE command.tenant_id = ? AND command.project_id = ?
                   AND command.connection_device_id = ?
                   AND command.status = 'ACCEPTED' AND command.deadline_at IS NULL
                   AND command.attempt_count < command.max_attempts
                   -- 只提前、不推后：已经到期的命令由既有扫描正常处理。
                   AND command.next_attempt_at > clock_timestamp()
                   -- 正在被其它扫描实例租约处理时不插手，避免两个写者对同一代次交错。
                   AND (command.retry_leased_until IS NULL OR command.retry_leased_until <= clock_timestamp())
                   AND EXISTS (SELECT 1 FROM ts_device_command_attempt attempt
                       WHERE attempt.tenant_id = command.tenant_id
                         AND attempt.project_id = command.project_id
                         AND attempt.command_id = command.id
                         AND attempt.attempt_no = command.attempt_count
                         AND attempt.status = 'FAILED'
                         AND attempt.error_code = 'DISPATCH_DEVICE_OFFLINE')
                """, tenantId, projectId, deviceId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DueCommand> claimDue(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("命令领取上限必须在1至100之间");
        // ADR0070：领取必须独立提交，业务事务持有的旧Due不能靠三UUID冒充下一代领取。
        return jdbcTemplate.query("SELECT tenant_id, project_id, command_id, retry_token, expected_attempt, claim_kind FROM claim_due_device_commands(?)",
                (rs, row) -> new DueCommand(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getObject("command_id", UUID.class),
                        rs.getObject("retry_token", UUID.class), rs.getInt("expected_attempt"), ClaimKind.valueOf(rs.getString("claim_kind"))), limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean consumeRetryClaim(DueCommand due) {
        if (due == null || due.tenantId() == null || due.projectId() == null || due.commandId() == null
                || due.retryToken() == null || due.expectedAttempt() < 1 || due.claimKind() == null) return false;
        // 项目/命令锁等待可能耗尽原lease；最终SQL用实时时钟，不采用调用方可回拨的时间。
        return jdbcTemplate.update("""
                UPDATE ts_device_command c SET retry_token = NULL, retry_leased_until = NULL
                 WHERE c.tenant_id = ? AND c.project_id = ? AND c.id = ?
                   AND c.retry_token = ? AND c.retry_leased_until > clock_timestamp()
                   AND c.attempt_count = ? AND c.next_attempt_at <= clock_timestamp()
                   AND (CASE
                    WHEN EXISTS (SELECT 1 FROM ts_device_command_attempt a
                       WHERE a.tenant_id = c.tenant_id AND a.project_id = c.project_id
                         AND a.command_id = c.id AND a.attempt_no = c.attempt_count
                         AND (c.status = 'ACCEPTED' AND c.deadline_at <= clock_timestamp() AND a.status = 'PENDING' AND a.deadline_at <= clock_timestamp())) THEN 'PUSH_PENDING_TIMEOUT'
                    WHEN EXISTS (SELECT 1 FROM ts_device_command_attempt a
                       WHERE a.tenant_id = c.tenant_id AND a.project_id = c.project_id
                         AND a.command_id = c.id AND a.attempt_no = c.attempt_count
                         AND (c.status IN ('DISPATCHED', 'ACKNOWLEDGED') AND c.deadline_at <= clock_timestamp() AND a.status IN ('PUBLISHED', 'ACKNOWLEDGED'))) THEN 'PUSH_RESPONSE_TIMEOUT'
                    WHEN EXISTS (SELECT 1 FROM ts_device_command_attempt a
                       WHERE a.tenant_id = c.tenant_id AND a.project_id = c.project_id
                         AND a.command_id = c.id AND a.attempt_no = c.attempt_count
                         AND (c.status = 'ACCEPTED' AND c.deadline_at IS NULL AND a.status = 'FAILED')) THEN 'PUSH_DISPATCH_RETRY'
                    WHEN c.attempt_count >= c.max_attempts AND c.status IN ('DISPATCHED', 'ACKNOWLEDGED')
                       AND c.deadline_at <= clock_timestamp()
                       AND EXISTS (SELECT 1 FROM ts_device_command_claim l
                           WHERE l.tenant_id = c.tenant_id AND l.project_id = c.project_id
                             AND l.command_id = c.id AND l.attempt_no = c.attempt_count) THEN 'PULL_FINAL_TIMEOUT'
                    END) = ?
                """, due.tenantId(), due.projectId(), due.commandId(), due.retryToken(), due.expectedAttempt(),
                due.claimKind().name()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean stopForProjectFreeze(UUID tenantId, UUID projectId, UUID commandId,
                                                 int expectedAttempt, Instant at) {
        return stopDelivery(tenantId, projectId, commandId, expectedAttempt, at,
                "PROJECT_FROZEN", "项目已冻结，停止命令交付", false);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean stopForPropertyCapability(UUID tenantId, UUID projectId, UUID commandId,
            int expectedAttempt, Instant at) {
        return stopDelivery(tenantId, projectId, commandId, expectedAttempt, at,
                "PROPERTY_SET_PROTOCOL_UNSUPPORTED", "当前接收设备不具备MQTT属性设置能力", true);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean stopForUnavailableReceiver(UUID tenantId, UUID projectId, UUID commandId,
            int expectedAttempt, Instant at) {
        return stopDelivery(tenantId, projectId, commandId, expectedAttempt, at,
                "COMMAND_ROUTE_UNAVAILABLE", "原命令接收关系已失效，停止交付", false);
    }

    /** 调用方已持业务锁且核实准入/领取；保留已发生的派发失败和响应超时诊断。 */
    private boolean stopDelivery(UUID tenantId, UUID projectId, UUID commandId, int expectedAttempt, Instant at,
            String reason, String message, boolean propertyOnly) {
        String attemptStatus = jdbcTemplate.query("""
                SELECT status FROM ts_device_command_attempt
                 WHERE tenant_id = ? AND project_id = ? AND command_id = ? AND attempt_no = ?
                """, (rs, row) -> rs.getString(1), tenantId, projectId, commandId, expectedAttempt)
                .stream().findFirst().orElse(null);
        if (attemptStatus == null) return false;
        int parent = jdbcTemplate.update("""
                UPDATE ts_device_command c
                   SET status = 'FAILED', failure_code = ?, failure_message = ?,
                       completed_at = ?, deadline_at = NULL, next_attempt_at = NULL,
                       retry_token = NULL, retry_leased_until = NULL, updated_at = now()
                 WHERE c.tenant_id = ? AND c.project_id = ? AND c.id = ? AND c.attempt_count = ?
                   AND (NOT ? OR c.operation_type='PROPERTY_SET')
                   AND EXISTS (SELECT 1 FROM ts_device_command_attempt a
                       WHERE a.tenant_id = c.tenant_id AND a.project_id = c.project_id
                         AND a.command_id = c.id AND a.attempt_no = c.attempt_count
                         AND ((c.status = 'ACCEPTED' AND a.status = 'PENDING')
                           OR (c.status = 'ACCEPTED' AND c.deadline_at IS NULL AND a.status = 'FAILED'
                               AND c.next_attempt_at <= clock_timestamp())
                           OR (c.status IN ('DISPATCHED', 'ACKNOWLEDGED') AND a.status IN ('PUBLISHED', 'ACKNOWLEDGED')
                               AND c.deadline_at <= clock_timestamp() AND c.next_attempt_at <= clock_timestamp())))
                """, reason, message, time(at), tenantId, projectId, commandId, expectedAttempt, propertyOnly);
        if (parent == 0) return false;
        if ("FAILED".equals(attemptStatus)) return true;
        boolean pending = "PENDING".equals(attemptStatus);
        int attempt = jdbcTemplate.update("""
                UPDATE ts_device_command_attempt SET status = ?, error_code = ?, error_message = ?,
                       completed_at = ?, updated_at = now()
                 WHERE tenant_id = ? AND project_id = ? AND command_id = ? AND attempt_no = ? AND status = ?
                """, pending ? "FAILED" : "TIMED_OUT", pending ? reason : "RESPONSE_TIMEOUT",
                pending ? message : "设备未在响应窗口内完成命令", time(at),
                tenantId, projectId, commandId, expectedAttempt, attemptStatus);
        requireChanged(attempt, "已终止父命令但当前尝试CAS失败");
        return true;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public RetryDecision prepareRetry(UUID projectId, UUID commandId,
                                                int currentAttempt, int maxAttempts, Instant now) {
        int attempt = jdbcTemplate.update("""
                UPDATE ts_device_command_attempt
                   SET status = 'TIMED_OUT', completed_at = ?, error_code = 'RESPONSE_TIMEOUT',
                       error_message = '设备未在响应窗口内完成命令', updated_at = now()
                 WHERE project_id = ? AND command_id = ? AND attempt_no = ?
                   AND status IN ('PUBLISHED', 'ACKNOWLEDGED')
                """, time(now), projectId, commandId, currentAttempt);
        if (attempt == 0) return RetryDecision.NOOP;
        if (currentAttempt >= maxAttempts) {
            int terminal = jdbcTemplate.update("""
                    UPDATE ts_device_command
                       SET status = 'TIMED_OUT', completed_at = ?, failure_code = 'RESPONSE_TIMEOUT',
                           failure_message = '命令三次派发均未在响应窗口内完成', deadline_at = NULL,
                           next_attempt_at = NULL, retry_token = NULL, retry_leased_until = NULL, updated_at = now()
                     WHERE project_id = ? AND id = ? AND attempt_count = ?
                       AND status IN ('DISPATCHED', 'ACKNOWLEDGED')
                    """, time(now), projectId, commandId, currentAttempt);
            requireChanged(terminal, "已超时当前尝试但父命令终态CAS失败");
            return RetryDecision.TIMED_OUT;
        }
        int retry = jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET status = 'ACCEPTED', deadline_at = NULL, next_attempt_at = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND attempt_count = ?
                   AND status IN ('DISPATCHED', 'ACKNOWLEDGED')
                """, time(now), projectId, commandId, currentAttempt);
        requireChanged(retry, "已超时当前尝试但父命令重试CAS失败");
        return RetryDecision.RETRY;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean timeoutFinalClaim(UUID projectId, UUID commandId, int expectedAttempt, Instant at) {
        return jdbcTemplate.update("""
                UPDATE ts_device_command c
                   SET status = 'TIMED_OUT', completed_at = ?, failure_code = 'RESPONSE_TIMEOUT',
                       failure_message = '领取次数耗尽且响应窗口已到期', deadline_at = NULL,
                       next_attempt_at = NULL, retry_token = NULL, retry_leased_until = NULL, updated_at = now()
                 WHERE c.project_id = ? AND c.id = ? AND c.attempt_count = ?
                   AND c.attempt_count >= c.max_attempts AND c.status IN ('DISPATCHED', 'ACKNOWLEDGED')
                   AND c.deadline_at <= clock_timestamp()
                   AND EXISTS (SELECT 1 FROM ts_device_command_claim l
                       WHERE l.tenant_id = c.tenant_id AND l.project_id = c.project_id
                         AND l.command_id = c.id AND l.attempt_no = c.attempt_count)
                """, time(at), projectId, commandId, expectedAttempt) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public double oldestPendingAgeSeconds() {
        return jdbcTemplate.queryForObject("SELECT device_command_oldest_pending_age()", Double.class);
    }

    /** 命令全列查询片段，集中避免两个读取入口字段漂移。 */
    private static String select() {
        return """
                SELECT id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                       operation_type, command_key, input_schema, output_schema, request_payload, response_payload, status,
                       idempotency_key, requested_by, app_user_id, timeout_seconds, attempt_count, max_attempts,
                       next_attempt_at, deadline_at, failure_code, failure_message, trace_id,
                       accepted_at, dispatched_at, acknowledged_at, completed_at, updated_at
                  FROM ts_device_command
                """;
    }

    /** JDBC 命令行映射。 */
    private DeviceCommand mapCommand(ResultSet rs, int row) throws SQLException {
        return new DeviceCommand(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("target_device_id", UUID.class),
                rs.getObject("connection_device_id", UUID.class), rs.getObject("command_definition_id", UUID.class),
                com.things.link.shared.message.DeviceCommandDispatch.OperationType.valueOf(rs.getString("operation_type")),
                rs.getString("command_key"), rs.getString("input_schema"), rs.getString("output_schema"),
                rs.getString("request_payload"), rs.getString("response_payload"),
                DeviceCommand.Status.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getObject("requested_by", UUID.class), rs.getObject("app_user_id", UUID.class),
                rs.getInt("timeout_seconds"), rs.getInt("attempt_count"),
                rs.getInt("max_attempts"), instant(rs, "next_attempt_at"), instant(rs, "deadline_at"),
                rs.getString("failure_code"), rs.getString("failure_message"), rs.getString("trace_id"),
                instant(rs, "accepted_at"), instant(rs, "dispatched_at"), instant(rs, "acknowledged_at"),
                instant(rs, "completed_at"), instant(rs, "updated_at"));
    }

    /** JDBC 尝试行映射。 */
    private DeviceCommandAttempt mapAttempt(ResultSet rs, int row) throws SQLException {
        return new DeviceCommandAttempt(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("command_id", UUID.class), rs.getInt("attempt_no"),
                rs.getObject("outbox_event_id", UUID.class), rs.getObject("connection_device_id", UUID.class),
                rs.getString("topic"), DeviceCommandAttempt.Status.valueOf(rs.getString("status")),
                instant(rs, "deadline_at"), instant(rs, "created_at"));
    }

    /** 部分写后的必要CAS失败必须抛错，让原业务事务完整回滚（ADR0070决策1）。 */
    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw new IllegalStateException(message);
    }

    /** nullable Instant 到 JDBC。 */
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    /** nullable JDBC 时间到 Instant。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
