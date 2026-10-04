package com.things.link.alarm.infrastructure.persistence;

import com.things.link.alarm.domain.AlarmNotificationBinding;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.AlarmNotificationGroup;
import com.things.link.alarm.domain.AlarmNotificationRecipient;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 通知仓储；所有配置修改都使用 version CAS，投递去重由数据库唯一键裁决。 */
@Repository
public class JdbcAlarmNotificationRepository implements AlarmNotificationRepository {
    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate 数据库访问器
     */
    public JdbcAlarmNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<AlarmNotificationGroup> pageGroups(UUID projectId, String cursor, int limit) {
        return page(
                jdbcTemplate.query(
                        groupSelect() + pageWhere("project_id = ? AND deleted_at IS NULL"),
                        this::group,
                        projectId,
                        cursorTime(cursor),
                        cursorTime(cursor),
                        cursorId(cursor),
                        limit + 1),
                limit,
                AlarmNotificationGroup::createdAt,
                AlarmNotificationGroup::id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AlarmNotificationGroup> findGroup(UUID projectId, UUID id) {
        return one(
                groupSelect() + " WHERE project_id=? AND id=? AND deleted_at IS NULL",
                this::group,
                projectId,
                id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean createGroup(AlarmNotificationGroup v) {
        return jdbcTemplate.update(
                        "INSERT INTO alarm_notification_group"
                            + " (id,tenant_id,project_id,name,enabled,version,created_at,updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                        v.id(),
                        v.tenantId(),
                        v.projectId(),
                        v.name(),
                        v.enabled(),
                        v.version(),
                        time(v.createdAt()),
                        time(v.updatedAt()))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateGroup(AlarmNotificationGroup v) {
        return jdbcTemplate.update(
                        "UPDATE alarm_notification_group SET"
                            + " name=?,enabled=?,version=version+1,updated_at=? WHERE project_id=?"
                            + " AND id=? AND version=? AND deleted_at IS NULL",
                        v.name(),
                        v.enabled(),
                        time(v.updatedAt()),
                        v.projectId(),
                        v.id(),
                        v.version())
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteGroup(UUID projectId, UUID id, int version) {
        return softDelete("alarm_notification_group", projectId, id, version);
    }

    /** {@inheritDoc} */
    @Override
    public List<AlarmNotificationRecipient> listRecipients(UUID projectId, UUID groupId) {
        return jdbcTemplate.query(
                recipientSelect()
                        + " WHERE project_id=? AND group_id=? AND deleted_at IS NULL ORDER BY"
                        + " created_at,id",
                this::recipient,
                projectId,
                groupId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AlarmNotificationRecipient> findRecipient(UUID projectId, UUID id) {
        return one(
                recipientSelect() + " WHERE project_id=? AND id=? AND deleted_at IS NULL",
                this::recipient,
                projectId,
                id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean createRecipient(AlarmNotificationRecipient v) {
        return jdbcTemplate.update(
                        "INSERT INTO alarm_notification_recipient"
                            + " (id,tenant_id,project_id,group_id,channel,target,enabled,version,created_at,updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                        v.id(),
                        v.tenantId(),
                        v.projectId(),
                        v.groupId(),
                        v.channel().name(),
                        v.target(),
                        v.enabled(),
                        v.version(),
                        time(v.createdAt()),
                        time(v.updatedAt()))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateRecipient(AlarmNotificationRecipient v) {
        return jdbcTemplate.update(
                        "UPDATE alarm_notification_recipient SET"
                            + " channel=?,target=?,enabled=?,version=version+1,updated_at=? WHERE"
                            + " project_id=? AND id=? AND version=? AND deleted_at IS NULL",
                        v.channel().name(),
                        v.target(),
                        v.enabled(),
                        time(v.updatedAt()),
                        v.projectId(),
                        v.id(),
                        v.version())
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteRecipient(UUID projectId, UUID id, int version) {
        return softDelete("alarm_notification_recipient", projectId, id, version);
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<AlarmNotificationTemplate> pageTemplates(
            UUID projectId, String cursor, int limit) {
        return page(
                jdbcTemplate.query(
                        templateSelect() + pageWhere("project_id=? AND deleted_at IS NULL"),
                        this::template,
                        projectId,
                        cursorTime(cursor),
                        cursorTime(cursor),
                        cursorId(cursor),
                        limit + 1),
                limit,
                AlarmNotificationTemplate::createdAt,
                AlarmNotificationTemplate::id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AlarmNotificationTemplate> findTemplate(UUID projectId, UUID id) {
        return one(
                templateSelect() + " WHERE project_id=? AND id=? AND deleted_at IS NULL",
                this::template,
                projectId,
                id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean createTemplate(AlarmNotificationTemplate v) {
        return jdbcTemplate.update(
                        "INSERT INTO alarm_notification_template"
                            + " (id,tenant_id,project_id,name,channel,subject_template,body_template,enabled,version,created_at,updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        v.id(),
                        v.tenantId(),
                        v.projectId(),
                        v.name(),
                        v.channel().name(),
                        v.subjectTemplate(),
                        v.bodyTemplate(),
                        v.enabled(),
                        v.version(),
                        time(v.createdAt()),
                        time(v.updatedAt()))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateTemplate(AlarmNotificationTemplate v) {
        return jdbcTemplate.update(
                        "UPDATE alarm_notification_template SET"
                            + " name=?,channel=?,subject_template=?,body_template=?,enabled=?,version=version+1,updated_at=?"
                            + " WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL",
                        v.name(),
                        v.channel().name(),
                        v.subjectTemplate(),
                        v.bodyTemplate(),
                        v.enabled(),
                        time(v.updatedAt()),
                        v.projectId(),
                        v.id(),
                        v.version())
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteTemplate(UUID projectId, UUID id, int version) {
        return softDelete("alarm_notification_template", projectId, id, version);
    }

    /** {@inheritDoc} */
    @Override
    public List<AlarmNotificationBinding> listBindings(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(
                bindingSelect()
                        + " WHERE project_id=? AND rule_id=? AND deleted_at IS NULL ORDER BY"
                        + " created_at,id",
                this::binding,
                projectId,
                ruleId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AlarmNotificationBinding> findBinding(UUID projectId, UUID id) {
        return one(
                bindingSelect() + " WHERE project_id=? AND id=? AND deleted_at IS NULL",
                this::binding,
                projectId,
                id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean createBinding(AlarmNotificationBinding v) {
        return jdbcTemplate.update(
                        "INSERT INTO alarm_notification_binding"
                            + " (id,tenant_id,project_id,rule_id,group_id,template_id,channel,enabled,version,created_at,updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        v.id(),
                        v.tenantId(),
                        v.projectId(),
                        v.ruleId(),
                        v.groupId(),
                        v.templateId(),
                        v.channel().name(),
                        v.enabled(),
                        v.version(),
                        time(v.createdAt()),
                        time(v.updatedAt()))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateBinding(AlarmNotificationBinding v) {
        return jdbcTemplate.update(
                        "UPDATE alarm_notification_binding SET"
                            + " group_id=?,template_id=?,channel=?,enabled=?,version=version+1,updated_at=?"
                            + " WHERE project_id=? AND id=? AND version=? AND deleted_at IS NULL",
                        v.groupId(),
                        v.templateId(),
                        v.channel().name(),
                        v.enabled(),
                        time(v.updatedAt()),
                        v.projectId(),
                        v.id(),
                        v.version())
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteBinding(UUID projectId, UUID id, int version) {
        return softDelete("alarm_notification_binding", projectId, id, version);
    }

    /** {@inheritDoc} */
    @Override
    public List<DeliveryTarget> findDeliveryTargets(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(
                """
SELECT b.id b_id,b.tenant_id b_tenant_id,b.project_id b_project_id,b.rule_id b_rule_id,
       b.group_id b_group_id,b.template_id b_template_id,b.channel b_channel,b.enabled b_enabled,
       b.version b_version,b.created_at b_created_at,b.updated_at b_updated_at,b.deleted_at b_deleted_at,
       r.id r_id,r.tenant_id r_tenant_id,r.project_id r_project_id,r.group_id r_group_id,
       r.channel r_channel,r.target r_target,r.enabled r_enabled,r.version r_version,
       r.created_at r_created_at,r.updated_at r_updated_at,r.deleted_at r_deleted_at,
       t.id t_id,t.tenant_id t_tenant_id,t.project_id t_project_id,t.name t_name,t.channel t_channel,
       t.subject_template t_subject_template,t.body_template t_body_template,t.enabled t_enabled,
       t.version t_version,t.created_at t_created_at,t.updated_at t_updated_at,t.deleted_at t_deleted_at
  FROM alarm_notification_binding b
  JOIN alarm_notification_group g ON g.project_id=b.project_id AND g.id=b.group_id AND g.enabled AND g.deleted_at IS NULL
  JOIN alarm_notification_recipient r ON r.project_id=b.project_id AND r.group_id=b.group_id AND r.channel=b.channel AND r.enabled AND r.deleted_at IS NULL
  JOIN alarm_notification_template t ON t.project_id=b.project_id AND t.id=b.template_id AND t.channel=b.channel AND t.enabled AND t.deleted_at IS NULL
 WHERE b.project_id=? AND b.rule_id=? AND b.channel IN ('EMAIL','WEBHOOK')
   AND b.enabled AND b.deleted_at IS NULL
""",
                (rs, row) ->
                        new DeliveryTarget(
                                binding(rs, "b_"), recipient(rs, "r_"), template(rs, "t_")),
                projectId,
                ruleId);
    }

    /** {@inheritDoc} */
    @Override
    public List<PushDeliveryRoute> findPushDeliveryRoutes(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(
                """
SELECT b.id b_id,b.tenant_id b_tenant_id,b.project_id b_project_id,b.rule_id b_rule_id,
       b.group_id b_group_id,b.template_id b_template_id,b.channel b_channel,b.enabled b_enabled,
       b.version b_version,b.created_at b_created_at,b.updated_at b_updated_at,b.deleted_at b_deleted_at,
       t.id t_id,t.tenant_id t_tenant_id,t.project_id t_project_id,t.name t_name,t.channel t_channel,
       t.subject_template t_subject_template,t.body_template t_body_template,t.enabled t_enabled,
       t.version t_version,t.created_at t_created_at,t.updated_at t_updated_at,t.deleted_at t_deleted_at
  FROM alarm_notification_binding b
  JOIN alarm_notification_group g
    ON g.project_id=b.project_id AND g.id=b.group_id AND g.enabled AND g.deleted_at IS NULL
  JOIN alarm_notification_template t
    ON t.project_id=b.project_id AND t.id=b.template_id AND t.channel='PUSH'
   AND t.enabled AND t.deleted_at IS NULL
 WHERE b.project_id=? AND b.rule_id=? AND b.channel='PUSH'
   AND b.enabled AND b.deleted_at IS NULL
 ORDER BY b.id
""",
                (resultSet, rowNumber) -> new PushDeliveryRoute(
                        binding(resultSet, "b_"), template(resultSet, "t_")),
                projectId,
                ruleId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean createDelivery(AlarmNotificationDelivery v) {
        return jdbcTemplate.update(
                        """
INSERT INTO alarm_notification_delivery (id,tenant_id,project_id,instance_id,alarm_event_id,binding_id,recipient_id,app_user_id,push_token_id,channel,target_snapshot,subject_snapshot,body_snapshot,template_version,status,attempt_count,max_attempts,next_attempt_at,last_outbox_event_id,provider_message_id,last_error_code,created_at,updated_at,terminal_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT DO NOTHING
""",
                        v.id(),
                        v.tenantId(),
                        v.projectId(),
                        v.instanceId(),
                        v.alarmEventId(),
                        v.bindingId(),
                        v.recipientId(),
                        v.appUserId(),
                        v.pushTokenId(),
                        v.channel().name(),
                        v.targetSnapshot(),
                        v.subjectSnapshot(),
                        v.bodySnapshot(),
                        v.templateVersion(),
                        v.status().name(),
                        v.attemptCount(),
                        v.maxAttempts(),
                        time(v.nextAttemptAt()),
                        v.lastOutboxEventId(),
                        v.providerMessageId(),
                        v.lastErrorCode(),
                        time(v.createdAt()),
                        time(v.updatedAt()),
                        time(v.terminalAt()))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AlarmNotificationDelivery> findDelivery(UUID projectId, UUID deliveryId) {
        return jdbcTemplate
                .query(
                        deliverySelect() + " WHERE project_id=? AND id=?",
                        this::delivery,
                        projectId,
                        deliveryId)
                .stream()
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean acceptQueuedDelivery(
            UUID projectId, UUID deliveryId, UUID eventId, int attemptNo) {
        Boolean accepted = jdbcTemplate.queryForObject(
                """
SELECT EXISTS(
    SELECT 1 FROM alarm_notification_delivery
     WHERE project_id=? AND id=? AND last_outbox_event_id=?
       AND status='QUEUED' AND attempt_count=? AND attempt_count < max_attempts)
""",
                Boolean.class,
                projectId,
                deliveryId,
                eventId,
                attemptNo - 1);
        return Boolean.TRUE.equals(accepted);
    }

    /** {@inheritDoc} */
    @Override
    public boolean startDelivery(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            UUID dispatchLeaseToken,
            Instant startedAt,
            Instant recoveryAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='SENDING', attempt_count=?, next_attempt_at=?, last_error_code=NULL,
       updated_at=?, retry_lease_token=NULL, retry_leased_until=NULL,
       dispatch_lease_token=NULL, dispatch_leased_until=NULL
 WHERE project_id=? AND id=? AND attempt_count=? AND attempt_count < max_attempts
   AND status='QUEUED' AND dispatch_lease_token=? AND dispatch_leased_until > ?
""",
                        attemptNo,
                        time(recoveryAt),
                        time(startedAt),
                        projectId,
                        deliveryId,
                        attemptNo - 1,
                        dispatchLeaseToken,
                        time(startedAt))
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public DispatchClaim claimDispatches(int limit, Duration leaseDuration) {
        UUID leaseToken = Uuid7.generate();
        List<DispatchCandidate> values = jdbcTemplate.query(
                "SELECT * FROM claim_alarm_notification_dispatches(?,?,?)",
                (r, row) -> new DispatchCandidate(
                        uuid(r, "id"),
                        uuid(r, "tenant_id"),
                        uuid(r, "project_id"),
                        uuid(r, "event_id"),
                        uuid(r, "instance_id"),
                        uuid(r, "alarm_event_id"),
                        r.getInt("attempt_no"),
                        r.getTimestamp("requested_at").toInstant(),
                        r.getString("trace_id")),
                leaseToken,
                limit,
                Math.toIntExact(leaseDuration.toSeconds()));
        return new DispatchClaim(leaseToken, List.copyOf(values));
    }

    /** {@inheritDoc} */
    @Override
    public DispatchAges dispatchAges() {
        return jdbcTemplate.queryForObject(
                "SELECT queued_seconds,sending_seconds FROM alarm_notification_dispatch_ages()",
                (result, row) -> new DispatchAges(
                        Duration.ofSeconds(result.getLong("queued_seconds")),
                        Duration.ofSeconds(result.getLong("sending_seconds"))));
    }

    /** {@inheritDoc} */
    @Override
    public boolean releaseDispatch(
            UUID projectId, UUID deliveryId, UUID leaseToken, Duration retryDelay) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT release_alarm_notification_dispatch(?,?,?,?)",
                Boolean.class,
                projectId,
                deliveryId,
                leaseToken,
                Math.toIntExact(retryDelay.toSeconds())));
    }

    /** {@inheritDoc} */
    @Override
    public boolean markDeliverySucceeded(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String providerMessageId,
            Instant completedAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='SUCCEEDED', provider_message_id=?, last_error_code=NULL,
       updated_at=?, terminal_at=?
 WHERE project_id=? AND id=? AND status='SENDING' AND attempt_count=?
""",
                        providerMessageId,
                        time(completedAt),
                        time(completedAt),
                        projectId,
                        deliveryId,
                        attemptNo)
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean markDeliveryRetry(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            Instant nextAttemptAt,
            String errorCode,
            Instant updatedAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='RETRY_SCHEDULED', next_attempt_at=?, last_error_code=?, updated_at=?
 WHERE project_id=? AND id=? AND status='SENDING' AND attempt_count=?
   AND attempt_count < max_attempts
""",
                        time(nextAttemptAt),
                        errorCode,
                        time(updatedAt),
                        projectId,
                        deliveryId,
                        attemptNo)
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean markDeliveryDeadLetter(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String errorCode,
            Instant completedAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='DEAD_LETTER', next_attempt_at=NULL, last_error_code=?,
       updated_at=?, terminal_at=?
 WHERE project_id=? AND id=? AND status='SENDING' AND attempt_count=?
""",
                        errorCode,
                        time(completedAt),
                        time(completedAt),
                        projectId,
                        deliveryId,
                        attemptNo)
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean markDeliverySkippedAuthorization(
            UUID projectId, UUID deliveryId, int attemptNo, Instant completedAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='SKIPPED_AUTHORIZATION', next_attempt_at=NULL,
       provider_message_id=NULL, last_error_code='AUTHORIZATION_REVOKED',
       updated_at=?, terminal_at=?
 WHERE project_id=? AND id=? AND channel='PUSH'
   AND status='SENDING' AND attempt_count=?
""",
                        time(completedAt),
                        time(completedAt),
                        projectId,
                        deliveryId,
                        attemptNo)
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public RetryClaim claimRetries(int limit, Duration leaseDuration) {
        UUID leaseToken = Uuid7.generate();
        List<RetryCandidate> values =
                jdbcTemplate.query(
                        "SELECT * FROM claim_alarm_notification_retries(?,?,?)",
                        (r, row) ->
                                new RetryCandidate(
                                        uuid(r, "id"),
                                        uuid(r, "tenant_id"),
                                        uuid(r, "project_id"),
                                        uuid(r, "instance_id"),
                                        uuid(r, "alarm_event_id"),
                                        r.getInt("next_attempt_no")),
                        leaseToken,
                        limit,
                        Math.toIntExact(leaseDuration.toSeconds()));
        return new RetryClaim(leaseToken, List.copyOf(values));
    }

    /** {@inheritDoc} */
    @Override
    public boolean requeueClaimedRetry(
            UUID projectId,
            UUID deliveryId,
            UUID leaseToken,
            UUID outboxEventId,
            int nextAttemptNo,
            Instant updatedAt) {
        return jdbcTemplate.update(
                        """
UPDATE alarm_notification_delivery
   SET status='QUEUED', attempt_count=?, last_outbox_event_id=?, next_attempt_at=NULL,
       retry_lease_token=NULL, retry_leased_until=NULL, updated_at=?
 WHERE project_id=? AND id=?
   AND retry_lease_token=? AND retry_leased_until > clock_timestamp()
   AND next_attempt_at <= clock_timestamp()
   AND ((status='RETRY_SCHEDULED' AND attempt_count < max_attempts AND attempt_count + 1 = ?)
     OR (status='SENDING' AND attempt_count = ? AND attempt_count BETWEEN 1 AND max_attempts))
""",
                        nextAttemptNo - 1,
                        outboxEventId,
                        time(updatedAt),
                        projectId,
                        deliveryId,
                        leaseToken,
                        nextAttemptNo,
                        nextAttemptNo)
                == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean stopClaimedRetryForProjectFreeze(UUID tenantId, UUID projectId, UUID deliveryId,
                                                  UUID retryToken, int expectedAttemptNo, Instant now) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT alarm_notification_stop_frozen_retry(?,?,?,?,?,?)", Boolean.class,
                tenantId, projectId, deliveryId, retryToken, expectedAttemptNo, time(now)));
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<AlarmNotificationDelivery> pageDeliveries(
            UUID projectId, UUID instanceId, String cursor, int limit) {
        String predicate = instanceId == null ? "project_id=?" : "project_id=? AND instance_id=?";
        Object[] args =
                instanceId == null
                        ? new Object[] {
                            projectId,
                            cursorTime(cursor),
                            cursorTime(cursor),
                            cursorId(cursor),
                            limit + 1
                        }
                        : new Object[] {
                            projectId,
                            instanceId,
                            cursorTime(cursor),
                            cursorTime(cursor),
                            cursorId(cursor),
                            limit + 1
                        };
        return page(
                jdbcTemplate.query(deliverySelect() + pageWhere(predicate), this::delivery, args),
                limit,
                AlarmNotificationDelivery::createdAt,
                AlarmNotificationDelivery::id);
    }

    /** 用受控表名软删配置表；该方法只调用本类常量，不能接收 HTTP 输入。 */
    private boolean softDelete(String table, UUID projectId, UUID id, int version) {
        return jdbcTemplate.update(
                        "UPDATE "
                                + table
                                + " SET deleted_at=now(),updated_at=now(),version=version+1 WHERE"
                                + " project_id=? AND id=? AND version=? AND deleted_at IS NULL",
                        projectId,
                        id,
                        version)
                == 1;
    }

    /**
     * @return 共用的键集翻页后半段
     */
    private static String pageWhere(String predicate) {
        return " WHERE "
                + predicate
                + " AND (?::timestamptz IS NULL OR (created_at,id) < (?::timestamptz,?::uuid))"
                + " ORDER BY created_at DESC,id DESC LIMIT ?";
    }

    /**
     * @return 单行或空
     */
    private <T> Optional<T> one(
            String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args).stream().findFirst();
    }

    /**
     * @return 分页结果
     */
    private static <T> CursorPage<T> page(
            List<T> rows,
            int limit,
            java.util.function.Function<T, Instant> time,
            java.util.function.Function<T, UUID> id) {
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<T> items = rows.subList(0, limit);
        T last = items.getLast();
        return CursorPage.of(items, encode(last == null ? null : time.apply(last), id.apply(last)));
    }

    /**
     * @return 通知组列
     */
    private static String groupSelect() {
        return "SELECT"
                   + " id,tenant_id,project_id,name,enabled,version,created_at,updated_at,deleted_at"
                   + " FROM alarm_notification_group";
    }

    /**
     * @return 收件人列
     */
    private static String recipientSelect() {
        return "SELECT"
                   + " id,tenant_id,project_id,group_id,channel,target,enabled,version,created_at,updated_at,deleted_at"
                   + " FROM alarm_notification_recipient";
    }

    /**
     * @return 模板列
     */
    private static String templateSelect() {
        return "SELECT"
                   + " id,tenant_id,project_id,name,channel,subject_template,body_template,enabled,version,created_at,updated_at,deleted_at"
                   + " FROM alarm_notification_template";
    }

    /**
     * @return 绑定列
     */
    private static String bindingSelect() {
        return "SELECT"
                   + " id,tenant_id,project_id,rule_id,group_id,template_id,channel,enabled,version,created_at,updated_at,deleted_at"
                   + " FROM alarm_notification_binding";
    }

    /**
     * @return 投递列
     */
    private static String deliverySelect() {
        return "SELECT"
                   + " id,tenant_id,project_id,instance_id,alarm_event_id,binding_id,recipient_id,app_user_id,push_token_id,channel,target_snapshot,subject_snapshot,body_snapshot,template_version,status,attempt_count,max_attempts,next_attempt_at,last_outbox_event_id,provider_message_id,last_error_code,created_at,updated_at,terminal_at"
                   + " FROM alarm_notification_delivery";
    }

    /**
     * @return 通知组
     */
    private AlarmNotificationGroup group(ResultSet r, int x) throws SQLException {
        return new AlarmNotificationGroup(
                uuid(r, "id"),
                uuid(r, "tenant_id"),
                uuid(r, "project_id"),
                r.getString("name"),
                r.getBoolean("enabled"),
                r.getInt("version"),
                instant(r, "created_at"),
                instant(r, "updated_at"),
                instant(r, "deleted_at"));
    }

    /**
     * @return 收件人
     */
    private AlarmNotificationRecipient recipient(ResultSet r, int x) throws SQLException {
        return recipient(r, "");
    }

    /**
     * @return 模板
     */
    private AlarmNotificationTemplate template(ResultSet r, int x) throws SQLException {
        return template(r, "");
    }

    /**
     * @return 绑定
     */
    private AlarmNotificationBinding binding(ResultSet r, int x) throws SQLException {
        return binding(r, "");
    }

    /**
     * @return 投递
     */
    private AlarmNotificationDelivery delivery(ResultSet r, int x) throws SQLException {
        return new AlarmNotificationDelivery(
                uuid(r, "id"),
                uuid(r, "tenant_id"),
                uuid(r, "project_id"),
                uuid(r, "instance_id"),
                uuid(r, "alarm_event_id"),
                uuid(r, "binding_id"),
                uuid(r, "recipient_id"),
                uuid(r, "app_user_id"),
                uuid(r, "push_token_id"),
                NotificationChannel.valueOf(r.getString("channel")),
                r.getString("target_snapshot"),
                r.getString("subject_snapshot"),
                r.getString("body_snapshot"),
                r.getInt("template_version"),
                AlarmNotificationDelivery.Status.valueOf(r.getString("status")),
                r.getInt("attempt_count"),
                r.getInt("max_attempts"),
                instant(r, "next_attempt_at"),
                uuid(r, "last_outbox_event_id"),
                r.getString("provider_message_id"),
                r.getString("last_error_code"),
                instant(r, "created_at"),
                instant(r, "updated_at"),
                instant(r, "terminal_at"));
    }

    /**
     * @return 收件人
     */
    private AlarmNotificationRecipient recipient(ResultSet r, String p) throws SQLException {
        return new AlarmNotificationRecipient(
                uuid(r, p + "id"),
                uuid(r, p + "tenant_id"),
                uuid(r, p + "project_id"),
                uuid(r, p + "group_id"),
                NotificationChannel.valueOf(r.getString(p + "channel")),
                r.getString(p + "target"),
                r.getBoolean(p + "enabled"),
                r.getInt(p + "version"),
                instant(r, p + "created_at"),
                instant(r, p + "updated_at"),
                instant(r, p + "deleted_at"));
    }

    /**
     * @return 模板
     */
    private AlarmNotificationTemplate template(ResultSet r, String p) throws SQLException {
        return new AlarmNotificationTemplate(
                uuid(r, p + "id"),
                uuid(r, p + "tenant_id"),
                uuid(r, p + "project_id"),
                r.getString(p + "name"),
                NotificationChannel.valueOf(r.getString(p + "channel")),
                r.getString(p + "subject_template"),
                r.getString(p + "body_template"),
                r.getBoolean(p + "enabled"),
                r.getInt(p + "version"),
                instant(r, p + "created_at"),
                instant(r, p + "updated_at"),
                instant(r, p + "deleted_at"));
    }

    /**
     * @return 绑定
     */
    private AlarmNotificationBinding binding(ResultSet r, String p) throws SQLException {
        return new AlarmNotificationBinding(
                uuid(r, p + "id"),
                uuid(r, p + "tenant_id"),
                uuid(r, p + "project_id"),
                uuid(r, p + "rule_id"),
                uuid(r, p + "group_id"),
                uuid(r, p + "template_id"),
                NotificationChannel.valueOf(r.getString(p + "channel")),
                r.getBoolean(p + "enabled"),
                r.getInt(p + "version"),
                instant(r, p + "created_at"),
                instant(r, p + "updated_at"),
                instant(r, p + "deleted_at"));
    }

    /**
     * @return nullable UUID
     */
    private static UUID uuid(ResultSet r, String c) throws SQLException {
        return r.getObject(c, UUID.class);
    }

    /**
     * @return nullable instant
     */
    private static Instant instant(ResultSet r, String c) throws SQLException {
        Timestamp t = r.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    /**
     * @return nullable JDBC instant
     */
    private static Timestamp time(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    /**
     * @return 游标时间
     */
    private static Timestamp cursorTime(String v) {
        return time(decode(v).time());
    }

    /**
     * @return 游标 UUID
     */
    private static UUID cursorId(String v) {
        return decode(v).id();
    }

    /**
     * @return 编码游标
     */
    private static String encode(Instant t, UUID id) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((t + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return 解码游标
     */
    private static Cursor decode(String v) {
        if (v == null || v.isBlank()) return new Cursor(null, null);
        try {
            String[] f =
                    new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8)
                            .split("\\|", 2);
            return new Cursor(Instant.parse(f[0]), UUID.fromString(f[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("告警通知游标不合法", e);
        }
    }

    /** 不透明游标。 */
    private record Cursor(Instant time, UUID id) {}
}
