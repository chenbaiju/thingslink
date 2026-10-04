package com.things.link.bootstrap.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.alarm.application.AlarmInstanceService;
import com.things.link.alarm.application.AlarmMetrics;
import com.things.link.alarm.application.AlarmRuleCommand;
import com.things.link.alarm.application.AlarmRuleService;
import com.things.link.alarm.application.NotificationConfigurationService;
import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmNotificationGroup;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.alarm.infrastructure.notification.DeterministicPushNotificationSender;
import com.things.link.enduser.application.AppPushTokenService;
import com.things.link.enduser.domain.AppPushToken;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.OverviewService;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import com.things.link.support.resilience.NotificationExternalGuard;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** S6-1 在真实 PostgreSQL/RLS 上验证告警规则、事故状态机与 HTTP 权限边界。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.listener.missing-topics-fatal=false"
})
@DisplayName("S6-1 告警状态机")
class AlarmLifecycleTests extends AbstractKafkaIntegrationTest {
    /** JSON 契约解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试账号口令。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** 版本化数据面夹具用 1.0.0 快照：仅一个 NUMBER 上报温度属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";

    /** 真实 MVC。 */
    @Autowired private MockMvc mockMvc;

    /** 仅创建跨项目成员夹具和读取账号 ID。 */
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 清空认证限流，避免重复注册测试互相影响。 */
    @Autowired private AuthRateLimiter rateLimiter;

    /** 真实规则配置服务。 */
    @Autowired private AlarmRuleService ruleService;

    /** 真实数值评估端口。 */
    @Autowired private AlarmEvaluationService evaluationService;

    /** 真实实例维护服务。 */
    @Autowired private AlarmInstanceService instanceService;

    /** 真实项目概要服务，用于验收 S5→S6 告警率事实接线。 */
    @Autowired private OverviewService overviewService;

    /** 生产 Micrometer 注册表，用于验证状态迁移实际写入低基数指标。 */
    @Autowired private MeterRegistry meterRegistry;

    /** 真实通知配置用例，用于验收激活事实到投递意图的事务接线。 */
    @Autowired private NotificationConfigurationService notificationService;

    /** 真实通知仓储，用于验证跨项目重试领取函数而不绕过生产实现。 */
    @Autowired private AlarmNotificationRepository notificationRepository;

    /** 真实 PUSH 安装实例服务，用于构造受 AES-GCM 保护的有效 audience 事实。 */
    @Autowired private AppPushTokenService pushTokenService;

    /** 生产通知渠道 bulkhead，用于确定性安排“先解绑、后发送前复核”的竞态顺序。 */
    @Autowired private NotificationExternalGuard notificationExternalGuard;

    /** 真实标准上行摄入端口，用于验证告警激活与遥测事实同事务提交（D-040）。 */
    @Autowired private PropertyIngestionService ingestionService;

    /** 真实规则动作告警入口，用于验证规则动作链不回滚告警事实（D-040）。 */
    @Autowired private RuleAlarmActionService ruleAlarmActionService;

    /** 真实 Kafka 生产端用于验收通知 listener、终态与重复投递吸收。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    /** A2b 动态 App 夹具租户；由 AfterEach 逆序清理，避免污染后续全仓隔离测试。 */
    private TenantScope pushFixtureScope;

    /** A2b 动态 App 夹具项目；与租户轴共同限定清理范围。 */
    private UUID pushFixtureProjectId;

    /** 每条直接服务调用都清理 ThreadLocal，防止污染其后的 HTTP 请求。 */
    @AfterEach
    void clearScope() {
        if (pushFixtureScope != null) {
            TenantContext.set(pushFixtureScope);
            // 逆外键顺序清理本场景创建的 A2b App 事实；项目/设备/告警通用夹具仍由既有测试基线负责。
            jdbcTemplate.update("DELETE FROM app_push_token WHERE tenant_id=?", pushFixtureScope.tenantId());
            jdbcTemplate.update("DELETE FROM app_user_device WHERE project_id=?", pushFixtureProjectId);
            jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id=?", pushFixtureProjectId);
            jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id=?", pushFixtureScope.tenantId());
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM app_user_role WHERE project_id=?",
                            Integer.class,
                            pushFixtureProjectId))
                    .isZero();
            pushFixtureScope = null;
            pushFixtureProjectId = null;
        }
        TenantContext.clear();
    }

    /** duration=0 首条即 ACTIVE，ACK 不改变条件，恢复回差与 clearDuration 共同决定自动清除。 */
    @Test
    void activatesImmediatelyKeepsAckOrthogonalAndRequiresContinuousRecovery() throws Exception {
        Fixture fixture = fixture("orthogonal");
        AlarmRule rule = createRule(fixture, "温度告警", 0, 10, 30, 25);
        Instant t0 = Instant.parse("2026-08-09T10:00:00Z");

        evaluate(fixture, rule, Uuid7.generate(), 31, t0);
        AlarmInstance active = onlyInstance(fixture);
        assertThat(active.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
        assertThat(events(fixture, active))
                .extracting(AlarmEvent::eventType)
                .containsExactly(AlarmEvent.EventType.ACTIVATED, AlarmEvent.EventType.PENDING);
        assertThat(
                        meterRegistry
                                .get(AlarmMetrics.TRANSITION)
                                .tag("event_type", "activated")
                                .counter()
                                .count())
                .isGreaterThanOrEqualTo(1D);
        assertThat(overview(fixture).alarmRate().available()).isTrue();
        assertThat(overview(fixture).alarmRate().value()).isEqualTo(1D);

        TenantContext.set(fixture.scope());
        AlarmInstance acknowledged =
                instanceService.acknowledge(fixture.projectId(), active.id(), active.version());
        assertThat(acknowledged.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
        assertThat(acknowledged.ackState()).isEqualTo(AlarmInstance.AckState.ACKNOWLEDGED);

        // t0+1 开始恢复候选；t0+2 又落入回差空档，计时必须重置而不能提前清除。
        evaluate(fixture, rule, Uuid7.generate(), 24, t0.plusSeconds(1));
        evaluate(fixture, rule, Uuid7.generate(), 28, t0.plusSeconds(2));
        evaluate(fixture, rule, Uuid7.generate(), 24, t0.plusSeconds(3));
        evaluate(fixture, rule, Uuid7.generate(), 24, t0.plusSeconds(12));
        assertThat(onlyInstance(fixture).conditionState())
                .isEqualTo(AlarmInstance.ConditionState.ACTIVE);
        evaluate(fixture, rule, Uuid7.generate(), 24, t0.plusSeconds(13));
        AlarmInstance cleared = onlyInstance(fixture);
        assertThat(cleared.conditionState()).isEqualTo(AlarmInstance.ConditionState.CLEARED);
        assertThat(cleared.clearReason()).isEqualTo(AlarmInstance.ClearReason.AUTO_RECOVERY);
        assertThat(cleared.ackState()).isEqualTo(AlarmInstance.AckState.ACKNOWLEDGED);
    }

    /** PENDING 需要连续 trigger；中断立即结束本代，之后重新满足条件必须产生新实例。 */
    @Test
    void requiresContinuousTriggerAndCreatesNewGenerationAfterClear() throws Exception {
        Fixture fixture = fixture("continuous");
        AlarmRule rule = createRule(fixture, "连续触发", 10, 0, 30, 25);
        Instant t0 = Instant.parse("2026-08-09T11:00:00Z");
        evaluate(fixture, rule, Uuid7.generate(), 31, t0);
        AlarmInstance first = onlyInstance(fixture);
        assertThat(first.conditionState()).isEqualTo(AlarmInstance.ConditionState.PENDING);
        evaluate(fixture, rule, Uuid7.generate(), 28, t0.plusSeconds(3));
        assertThat(onlyInstance(fixture).conditionState())
                .isEqualTo(AlarmInstance.ConditionState.CLEARED);

        evaluate(fixture, rule, Uuid7.generate(), 31, t0.plusSeconds(4));
        evaluate(fixture, rule, Uuid7.generate(), 31, t0.plusSeconds(14));
        List<AlarmInstance> instances = instances(fixture);
        assertThat(instances).hasSize(2);
        assertThat(instances).extracting(AlarmInstance::id).contains(first.id());
        assertThat(instances.getFirst().id()).isNotEqualTo(first.id());
        assertThat(instances.getFirst().conditionState())
                .isEqualTo(AlarmInstance.ConditionState.ACTIVE);
    }

    /** 同一 source message 不能重复追加迁移事件，乱序 receivedAt 不得倒退已接受状态。 */
    @Test
    void absorbsDuplicateAndOutOfOrderEvaluation() throws Exception {
        Fixture fixture = fixture("idempotent");
        AlarmRule rule = createRule(fixture, "幂等告警", 0, 0, 30, 25);
        Instant t0 = Instant.parse("2026-08-09T12:00:00Z");
        UUID message = Uuid7.generate();
        evaluate(fixture, rule, message, 31, t0);
        AlarmInstance active = onlyInstance(fixture);
        int eventCount = events(fixture, active).size();
        evaluate(fixture, rule, message, 31, t0.plusSeconds(1));
        evaluate(fixture, rule, Uuid7.generate(), 31, t0.minusSeconds(1));
        AlarmInstance after = onlyInstance(fixture);
        assertThat(events(fixture, after)).hasSize(eventCount);
        assertThat(after.lastReceivedAt()).isEqualTo(t0.plusSeconds(1));
        assertThat(after.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
    }

    /** ACTIVATED 真实事件恰展开一次 delivery+Outbox；重放、ACK、CLEAR 与后续模板修改均不篡改事实快照。 */
    @Test
    void createsSingleNotificationIntentFromActivatedEventAndFreezesSnapshot() throws Exception {
        Fixture fixture = fixture("notification-intent");
        AlarmRule rule = createRule(fixture, "通知规则", 0, 0, 30, 25);
        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "值班组", true);
        notificationService.createRecipient(
                fixture.projectId(),
                group.id(),
                NotificationChannel.EMAIL,
                "oncall@example.com",
                true);
        AlarmNotificationTemplate template =
                notificationService.createTemplate(
                        fixture.projectId(),
                        "邮件模板",
                        NotificationChannel.EMAIL,
                        "${alarm.type}",
                        "首次值=${alarm.value}",
                        true);
        notificationService.createBinding(
                fixture.projectId(),
                rule.id(),
                group.id(),
                template.id(),
                NotificationChannel.EMAIL,
                true);
        TenantContext.clear();

        Instant t0 = Instant.parse("2026-08-09T14:00:00Z");
        UUID messageId = Uuid7.generate();
        evaluate(fixture, rule, messageId, 31, t0);
        // evaluate 会在 finally 清理线程上下文；直接核对 RLS 事实前必须显式恢复当前项目。
        TenantContext.set(fixture.scope());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM alarm_notification_delivery WHERE"
                                        + " project_id=?",
                                Integer.class,
                                fixture.projectId()))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND"
                                        + " event_type='ALARM_NOTIFICATION_DELIVERY_REQUEST'",
                                Integer.class,
                                fixture.projectId()))
                .isEqualTo(1);
        String snapshot =
                jdbcTemplate.queryForObject(
                        "SELECT body_snapshot FROM alarm_notification_delivery WHERE project_id=?",
                        String.class,
                        fixture.projectId());
        assertThat(snapshot).isEqualTo("首次值=31.0");

        evaluate(fixture, rule, messageId, 31, t0.plusSeconds(1));
        TenantContext.set(fixture.scope());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM alarm_notification_delivery WHERE"
                                        + " project_id=?",
                                Integer.class,
                                fixture.projectId()))
                .isEqualTo(1);
        TenantContext.set(fixture.scope());
        notificationService.updateTemplate(
                fixture.projectId(),
                template.id(),
                "邮件模板",
                NotificationChannel.EMAIL,
                "新主题",
                "新正文",
                true,
                template.version());
        AlarmInstance active = onlyInstance(fixture);
        TenantContext.set(fixture.scope());
        instanceService.acknowledge(fixture.projectId(), active.id(), active.version());
        AlarmInstance afterAck = onlyInstance(fixture);
        TenantContext.set(fixture.scope());
        instanceService.clear(fixture.projectId(), afterAck.id(), afterAck.version());
        TenantContext.set(fixture.scope());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM alarm_notification_delivery WHERE"
                                        + " project_id=?",
                                Integer.class,
                                fixture.projectId()))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT body_snapshot FROM alarm_notification_delivery WHERE"
                                        + " project_id=?",
                                String.class,
                                fixture.projectId()))
                .isEqualTo("首次值=31.0");
        UUID deliveryId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM alarm_notification_delivery WHERE project_id=?",
                        UUID.class,
                        fixture.projectId());
        jdbcTemplate.update(
                "UPDATE alarm_notification_delivery SET status='RETRY_SCHEDULED',"
                        + " attempt_count=1,next_attempt_at=now()-interval '1 second' WHERE project_id=? AND id=?",
                fixture.projectId(),
                deliveryId);
        TenantContext.clear();
        AlarmNotificationRepository.RetryClaim claim =
                notificationRepository.claimRetries(10, Duration.ofSeconds(30));
        assertThat(claim.deliveries())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.id()).isEqualTo(deliveryId);
                    assertThat(candidate.projectId()).isEqualTo(fixture.projectId());
                    assertThat(candidate.nextAttemptNo()).isEqualTo(2);
                });
        TenantContext.clear();
    }

    /** G2-A2b：一次 PUSH 路由只按同时有效的用户、角色、设备关系和安装实例逐实例展开。 */
    @Test
    void expandsPushDeliveriesOnlyForActiveInstallations() throws Exception {
        Fixture fixture = fixture("push-expansion");
        pushFixtureScope = fixture.scope();
        pushFixtureProjectId = fixture.projectId();
        AlarmRule rule = createRule(fixture, "PUSH 通知规则", 0, 0, 30, 25);
        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "PUSH 路由组", true);
        AlarmNotificationTemplate template = notificationService.createTemplate(
                fixture.projectId(),
                "PUSH 模板",
                NotificationChannel.PUSH,
                "${alarm.type}",
                "当前值=${alarm.value}",
                true);
        notificationService.createBinding(
                fixture.projectId(), rule.id(), group.id(), template.id(), NotificationChannel.PUSH, true);

        UUID activeUser = createAppUser(fixture, "push-active", "ACTIVE");
        createAppRole(fixture, activeUser, "ACTIVE");
        createDeviceRelation(fixture, activeUser, "ACTIVE");
        registerPushToken(fixture, activeUser, UUID.randomUUID(), "active-one");
        registerPushToken(fixture, activeUser, UUID.randomUUID(), "active-two");

        UUID disabledRoleUser = createAppUser(fixture, "push-disabled-role", "ACTIVE");
        createAppRole(fixture, disabledRoleUser, "DISABLED");
        createDeviceRelation(fixture, disabledRoleUser, "ACTIVE");
        registerPushToken(fixture, disabledRoleUser, UUID.randomUUID(), "disabled-role");

        UUID closedRelationUser = createAppUser(fixture, "push-closed-relation", "ACTIVE");
        createAppRole(fixture, closedRelationUser, "ACTIVE");
        createDeviceRelation(fixture, closedRelationUser, "CLOSED");
        registerPushToken(fixture, closedRelationUser, UUID.randomUUID(), "closed-relation");

        UUID lockedUser = createAppUser(fixture, "push-locked", "LOCKED");
        createAppRole(fixture, lockedUser, "ACTIVE");
        createDeviceRelation(fixture, lockedUser, "ACTIVE");
        registerPushToken(fixture, lockedUser, UUID.randomUUID(), "locked-user");

        UUID revokedUser = createAppUser(fixture, "push-revoked", "ACTIVE");
        createAppRole(fixture, revokedUser, "ACTIVE");
        createDeviceRelation(fixture, revokedUser, "ACTIVE");
        UUID revokedInstallation = UUID.randomUUID();
        registerPushToken(fixture, revokedUser, revokedInstallation, "revoked-token");
        pushTokenService.revoke(fixture.actor().tenantId(), fixture.projectId(), revokedUser, revokedInstallation);
        List<UUID> expectedTokenIds = jdbcTemplate.queryForList(
                "SELECT id FROM app_push_token WHERE app_user_id=? AND status='ACTIVE' ORDER BY id",
                UUID.class,
                activeUser);
        TenantContext.clear();

        UUID messageId = Uuid7.generate();
        evaluate(fixture, rule, messageId, 31, Instant.parse("2026-08-31T10:00:00Z"));

        TenantContext.set(fixture.scope());
        List<Map<String, Object>> deliveries = jdbcTemplate.queryForList(
                """
                SELECT recipient_id, app_user_id, push_token_id, channel, target_snapshot,
                       subject_snapshot, body_snapshot, status
                  FROM alarm_notification_delivery
                 WHERE project_id=? AND channel='PUSH'
                 ORDER BY push_token_id
                """,
                fixture.projectId());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.get("recipient_id")).isNull();
            assertThat(delivery.get("app_user_id")).isEqualTo(activeUser);
            assertThat(delivery.get("target_snapshot")).isEqualTo("PUSH");
            assertThat(delivery.get("subject_snapshot")).isEqualTo("HIGH_TEMPERATURE");
            assertThat(delivery.get("body_snapshot")).isEqualTo("当前值=31.0");
        });
        assertThat(deliveries)
                .extracting(delivery -> (UUID) delivery.get("push_token_id"))
                .containsExactlyElementsOf(expectedTokenIds);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='ALARM_NOTIFICATION_DELIVERY_REQUEST'",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(2);
        // A2c 已解除领取暂停；函数定义不得残留 A2b 的 channel 排除条件。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT pg_get_functiondef('claim_alarm_notification_dispatches(uuid,integer,integer)'::regprocedure) LIKE '%delivery.channel <> ''PUSH''%'",
                        Boolean.class))
                .isFalse();
        List<UUID> deliveryIds = deliveries.stream()
                .map(delivery -> (UUID) delivery.get("push_token_id"))
                .map(tokenId -> jdbcTemplate.queryForObject(
                        "SELECT id FROM alarm_notification_delivery WHERE project_id=? AND push_token_id=?",
                        UUID.class,
                        fixture.projectId(),
                        tokenId))
                .toList();
        TenantContext.clear();
        for (UUID deliveryId : deliveryIds) {
            awaitDeliveryStatus(fixture, deliveryId, "SUCCEEDED");
        }
        TenantContext.set(fixture.scope());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_notification_delivery WHERE project_id=? AND channel='PUSH' AND status='SUCCEEDED'",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(2);

        // 同一上行消息重放被告警事件与 PUSH 部分唯一键共同吸收，不能新增第三条安装实例事实。
        evaluate(fixture, rule, messageId, 31, Instant.parse("2026-08-31T10:00:01Z"));
        TenantContext.set(fixture.scope());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_notification_delivery WHERE project_id=? AND channel='PUSH'",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(2);
        TenantContext.clear();
    }

    /**
     * G2-A2c：投递事实展开后若解绑、角色停用、用户锁定或 token 吊销先提交，发送前全部跳过且有效实例仍独立成功。
     */
    @Test
    void rechecksAuthorizationImmediatelyBeforePushAndIsolatesInstallations() throws Exception {
        Fixture fixture = fixture("push-pre-send");
        pushFixtureScope = fixture.scope();
        pushFixtureProjectId = fixture.projectId();
        AlarmRule rule = createRule(fixture, "PUSH 发送前复核", 0, 0, 30, 25);
        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "PUSH 复核组", true);
        AlarmNotificationTemplate template = notificationService.createTemplate(
                fixture.projectId(), "PUSH 复核模板", NotificationChannel.PUSH,
                "${alarm.type}", "当前值=${alarm.value}", true);
        notificationService.createBinding(
                fixture.projectId(), rule.id(), group.id(), template.id(), NotificationChannel.PUSH, true);

        UUID successfulUser = createAuthorizedPushUser(fixture, "push-success");
        UUID successfulToken = registerPushToken(
                fixture, successfulUser, UUID.randomUUID(), "mock:success");
        UUID permanentToken = registerPushToken(
                fixture, successfulUser, UUID.randomUUID(),
                DeterministicPushNotificationSender.PERMANENT_TOKEN);
        UUID closedUser = createAuthorizedPushUser(fixture, "push-close-before-send");
        UUID closedToken = registerPushToken(
                fixture, closedUser, UUID.randomUUID(), "mock:closed");
        UUID disabledUser = createAuthorizedPushUser(fixture, "push-disable-before-send");
        UUID disabledToken = registerPushToken(
                fixture, disabledUser, UUID.randomUUID(), "mock:disabled");
        UUID lockedUser = createAuthorizedPushUser(fixture, "push-lock-before-send");
        UUID lockedToken = registerPushToken(
                fixture, lockedUser, UUID.randomUUID(), "mock:locked");
        UUID revokedUser = createAuthorizedPushUser(fixture, "push-revoke-before-send");
        UUID revokedInstallation = UUID.randomUUID();
        UUID revokedToken = registerPushToken(
                fixture, revokedUser, revokedInstallation, "mock:revoked");
        TenantContext.clear();

        List<NotificationExternalGuard.Guard> occupied = occupyPushBulkhead();
        try {
            evaluate(fixture, rule, Uuid7.generate(), 31, Instant.parse("2026-09-01T01:00:00Z"));
            TenantContext.set(fixture.scope());
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM alarm_notification_delivery WHERE project_id=? AND channel='PUSH'",
                            Integer.class,
                            fixture.projectId()))
                    .isEqualTo(6);
            // bulkhead 拒绝不消耗 attempt，确保以下四种撤权均先于下一次发送前复核提交。
            jdbcTemplate.update(
                    "UPDATE app_user_device SET status='CLOSED', updated_at=now()"
                            + " WHERE project_id=? AND app_user_id=? AND status='ACTIVE'",
                    fixture.projectId(), closedUser);
            jdbcTemplate.update(
                    "UPDATE app_user_role SET status='DISABLED' WHERE project_id=? AND app_user_id=?",
                    fixture.projectId(), disabledUser);
            jdbcTemplate.update(
                    "UPDATE app_user SET status='LOCKED' WHERE tenant_id=? AND id=?",
                    fixture.actor().tenantId(), lockedUser);
            pushTokenService.revoke(
                    fixture.actor().tenantId(), fixture.projectId(), revokedUser, revokedInstallation);
            TenantContext.clear();
        } finally {
            occupied.forEach(NotificationExternalGuard.Guard::close);
        }

        UUID successfulDelivery = pushDeliveryId(fixture, successfulToken);
        awaitDeliveryStatus(fixture, successfulDelivery, "SUCCEEDED");
        awaitDeliveryStatus(fixture, pushDeliveryId(fixture, permanentToken), "DEAD_LETTER");
        for (UUID tokenId : List.of(closedToken, disabledToken, lockedToken, revokedToken)) {
            awaitDeliveryStatus(fixture, pushDeliveryId(fixture, tokenId), "SKIPPED_AUTHORIZATION");
        }

        TenantContext.set(fixture.scope());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_notification_delivery"
                                + " WHERE project_id=? AND channel='PUSH' AND status='SKIPPED_AUTHORIZATION'"
                                + " AND attempt_count=1 AND provider_message_id IS NULL"
                                + " AND last_error_code='AUTHORIZATION_REVOKED' AND terminal_at IS NOT NULL",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_notification_delivery"
                                + " WHERE project_id=? AND channel='PUSH' AND status='SUCCEEDED'",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_notification_delivery"
                                + " WHERE project_id=? AND channel='PUSH' AND status='DEAD_LETTER'"
                                + " AND attempt_count=1 AND last_error_code='PUSH_PROVIDER_REJECTED'",
                        Integer.class,
                        fixture.projectId()))
                .isEqualTo(1);
        TenantContext.clear();
    }

    /**
     * D-040：合法原始长度模板经变量替换后膨胀超限，告警与遥测事实仍同事务提交，仅通知意图为 TEMPLATE_INVALID。
     *
     * <p>不直接调用 AlarmEvaluationService，而是走标准上行摄入的完整事务，证明 inbox、时序点、影子、消息日志、
     * 告警实例与事件都不被模板渲染错误回滚；投递意图以 TEMPLATE_INVALID 终态留档且不写 Outbox。</p>
     */
    @Test
    void invalidRenderedTemplateStillCommitsAlarmAndTelemetryFacts() throws Exception {
        Fixture fixture = fixture("d040-template");
        AlarmRule rule = createRule(fixture, "D040 渲染超限", 0, 0, 30, 25);
        String subjectTemplate = "${alarm.instanceId}".repeat(13);
        String bodyTemplate = "${alarm.instanceId}".repeat(1052);
        // 前置条件：原始模板长度合法，超限只能来自渲染后的变量膨胀。
        assertThat(subjectTemplate).hasSizeLessThanOrEqualTo(256);
        assertThat(bodyTemplate).hasSizeLessThanOrEqualTo(20_000);

        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "D040 值班组", true);
        notificationService.createRecipient(
                fixture.projectId(), group.id(), NotificationChannel.EMAIL, "d040@example.com", true);
        AlarmNotificationTemplate template =
                notificationService.createTemplate(
                        fixture.projectId(), "D040 模板", NotificationChannel.EMAIL,
                        subjectTemplate, bodyTemplate, true);
        notificationService.createBinding(
                fixture.projectId(), rule.id(), group.id(), template.id(),
                NotificationChannel.EMAIL, true);
        TenantContext.clear();

        UUID messageId = Uuid7.generate();
        String traceId = messageId.toString().replace("-", "");
        StandardUplinkMessage message =
                new StandardUplinkMessage(
                        messageId, fixture.actor().tenantId(), fixture.projectId(), fixture.deviceId(), null,
                        TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                        StandardUplinkMessage.Type.PROPERTY_REPORT,
                        "1.0.0",
                        Instant.parse("2026-08-09T16:00:00Z"), Instant.parse("2026-08-09T16:00:00Z"),
                        traceId, Map.of("temperature", 31.5));
        ingestionService.ingest(message);

        TenantContext.set(fixture.scope());
        // 告警事实仍提交：实例 ACTIVE + 唯一 ACTIVATED 事件。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT condition_state FROM alarm_instance WHERE project_id=? AND alarm_type='HIGH_TEMPERATURE'",
                        String.class, fixture.projectId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_event WHERE project_id=? AND source_message_id=? AND event_type='ACTIVATED'",
                        Integer.class, fixture.projectId(), messageId))
                .isEqualTo(1);
        // 遥测事实仍提交：时序点、inbox、影子均落库。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ts_property_point WHERE message_id=?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_inbox_message WHERE message_id=?", Integer.class, messageId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reported ->> 'temperature' FROM dev_shadow WHERE device_id=?",
                        String.class, fixture.deviceId()))
                .isEqualTo("31.5");
        // 投递意图：TEMPLATE_INVALID 终态 + TEMPLATE_RENDER_INVALID 诊断 + 无 Outbox 悬空关联。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM alarm_notification_delivery WHERE project_id=?",
                        String.class, fixture.projectId()))
                .isEqualTo("TEMPLATE_INVALID");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT last_error_code FROM alarm_notification_delivery WHERE project_id=?",
                        String.class, fixture.projectId()))
                .isEqualTo("TEMPLATE_RENDER_INVALID");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT last_outbox_event_id FROM alarm_notification_delivery WHERE project_id=?",
                        UUID.class, fixture.projectId()))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='ALARM_NOTIFICATION_DELIVERY_REQUEST'",
                        Integer.class, fixture.projectId()))
                .isZero();
        // TEMPLATE_INVALID 是终态，重试领取函数只扫 RETRY_SCHEDULED/SENDING，结构上不可能领取到本项目该意图。
        AlarmNotificationRepository.RetryClaim claim =
                notificationRepository.claimRetries(100, Duration.ofSeconds(30));
        assertThat(claim.deliveries()).noneMatch(c -> c.projectId().equals(fixture.projectId()));
        TenantContext.clear();
    }

    /**
     * D-040 规则动作链：模板渲染膨胀时 RuleAlarmActionService 不回滚告警实例/事件，仅通知意图 TEMPLATE_INVALID。
     *
     * <p>与标准上行摄入链并列的第二条事务链；证明降级不在调用方边界被后续代码变化重新回滚。</p>
     */
    @Test
    void ruleActionChainStillCommitsAlarmWhenTemplateInvalid() throws Exception {
        Fixture fixture = fixture("d040-rule-action");
        AlarmRule rule = createRule(fixture, "D040 规则动作渲染超限", 0, 0, 30, 25);
        String subjectTemplate = "${alarm.instanceId}".repeat(13);
        String bodyTemplate = "${alarm.instanceId}".repeat(1052);

        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "D040 规则组", true);
        notificationService.createRecipient(
                fixture.projectId(), group.id(), NotificationChannel.EMAIL, "d040-rule@example.com", true);
        AlarmNotificationTemplate template =
                notificationService.createTemplate(
                        fixture.projectId(), "D040 规则模板", NotificationChannel.EMAIL,
                        subjectTemplate, bodyTemplate, true);
        notificationService.createBinding(
                fixture.projectId(), rule.id(), group.id(), template.id(),
                NotificationChannel.EMAIL, true);
        TenantContext.clear();

        Instant now = Instant.parse("2026-08-09T16:00:00Z");
        UUID messageId = Uuid7.generate();
        RuleAlarmActionInput input =
                new RuleAlarmActionInput(
                        messageId, fixture.actor().tenantId(), fixture.projectId(), rule.id(),
                        fixture.deviceId(), now.minusSeconds(1), now, "d040-rule-trace");
        TenantContext.set(fixture.scope());
        assertThat(ruleAlarmActionService.create(input).changed()).isTrue();

        // 告警事实提交：实例 ACTIVE + 唯一 ACTIVATED 事件。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT condition_state FROM alarm_instance WHERE project_id=? AND alarm_type='HIGH_TEMPERATURE'",
                        String.class, fixture.projectId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM alarm_event WHERE project_id=? AND source_message_id=? AND event_type='ACTIVATED'",
                        Integer.class, fixture.projectId(), messageId))
                .isEqualTo(1);
        // 投递意图：TEMPLATE_INVALID 终态 + 无 Outbox。
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM alarm_notification_delivery WHERE project_id=?",
                        String.class, fixture.projectId()))
                .isEqualTo("TEMPLATE_INVALID");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='ALARM_NOTIFICATION_DELIVERY_REQUEST'",
                        Integer.class, fixture.projectId()))
                .isZero();
        TenantContext.clear();
    }

    /**
     * 真实 Kafka 重投只能把未配置 SMTP 的投递推进一次死信终态，不能重复执行同一 attempt。
     *
     * <p>S7-5 不把外部供应商替身伪装为成功：测试环境故意保留 LoggingMailSender，并把最大尝试设为 1，因而可以验证
     * consumer、项目 RLS、状态 CAS、失败分类和终态幂等的完整组合链路。
     */
    @Test
    void consumesRealKafkaNotificationAndAbsorbsDuplicateDelivery() throws Exception {
        Fixture fixture = fixture("notification-kafka");
        AlarmRule rule = createRule(fixture, "Kafka 通知规则", 0, 0, 30, 25);
        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "Kafka 值班组", true);
        notificationService.createRecipient(
                fixture.projectId(),
                group.id(),
                NotificationChannel.EMAIL,
                "kafka-oncall@example.com",
                true);
        AlarmNotificationTemplate template =
                notificationService.createTemplate(
                        fixture.projectId(),
                        "Kafka 邮件模板",
                        NotificationChannel.EMAIL,
                        "设备告警",
                        "当前值 ${alarm.value}",
                        true);
        notificationService.createBinding(
                fixture.projectId(),
                rule.id(),
                group.id(),
                template.id(),
                NotificationChannel.EMAIL,
                true);
        TenantContext.clear();

        evaluate(
                fixture,
                rule,
                Uuid7.generate(),
                31,
                Instant.parse("2026-08-11T04:00:00Z"));
        TenantContext.set(fixture.scope());
        NotificationDeliveryRequest request = jdbcTemplate.queryForObject(
                """
                SELECT id, tenant_id, instance_id, alarm_event_id
                  FROM alarm_notification_delivery
                 WHERE project_id=?
                """,
                (resultSet, rowNum) -> new NotificationDeliveryRequest(
                        Uuid7.generate(),
                        resultSet.getObject("tenant_id", UUID.class),
                        fixture.projectId(),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("instance_id", UUID.class),
                        resultSet.getObject("alarm_event_id", UUID.class),
                        1,
                        Instant.now(),
                        "s7-notification-kafka"),
                fixture.projectId());
        jdbcTemplate.update(
                "UPDATE alarm_notification_delivery SET max_attempts=1 WHERE project_id=? AND id=?",
                fixture.projectId(),
                request.deliveryId());
        TenantContext.clear();

        kafkaTemplate.send("tc.notification", request.deliveryId().toString(), request)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        awaitDeliveryStatus(fixture, request.deliveryId(), "DEAD_LETTER");
        var duplicateSend = kafkaTemplate.send(
                        "tc.notification", request.deliveryId().toString(), request)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        TopicPartition duplicatePartition = new TopicPartition(
                duplicateSend.getRecordMetadata().topic(), duplicateSend.getRecordMetadata().partition());
        // Producer ack 只证明 Broker 已持久化；必须等固定 listener group 提交越过第二条 offset 才能证明 CAS 真正吸收了重投。
        awaitConsumerCommitted(
                "things-link-notification-delivery",
                duplicatePartition,
                duplicateSend.getRecordMetadata().offset() + 1L);

        TenantContext.set(fixture.scope());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM alarm_notification_delivery WHERE project_id=? AND id=?",
                        String.class,
                        fixture.projectId(),
                        request.deliveryId()))
                .isEqualTo("DEAD_LETTER");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM alarm_notification_delivery WHERE project_id=? AND id=?",
                        Integer.class,
                        fixture.projectId(),
                        request.deliveryId()))
                .isEqualTo(1);
        TenantContext.clear();
    }

    /** 严重超额仍保留告警与投递审计事实，但不创建新的外部通知 Outbox。 */
    @Test
    void suppressesNotificationSideEffectWhenDailyUsageIsDegraded() throws Exception {
        Fixture fixture = fixture("notification-quota");
        AlarmRule rule = createRule(fixture, "通知降级规则", 0, 0, 30, 25);
        TenantContext.set(fixture.scope());
        AlarmNotificationGroup group =
                notificationService.createGroup(fixture.projectId(), "降级值班组", true);
        notificationService.createRecipient(
                fixture.projectId(), group.id(), NotificationChannel.EMAIL,
                "degraded@example.com", true);
        AlarmNotificationTemplate template = notificationService.createTemplate(
                fixture.projectId(), "降级模板", NotificationChannel.EMAIL,
                "告警", "当前值 ${alarm.value}", true);
        notificationService.createBinding(
                fixture.projectId(), rule.id(), group.id(), template.id(),
                NotificationChannel.EMAIL, true);
        jdbcTemplate.update("""
                INSERT INTO sys_usage_counter_daily
                    (id,tenant_id,project_id,usage_date,metric,used_value)
                VALUES (?,?,?,?, 'NOTIFICATION_DELIVERY', ?)
                """,
                Uuid7.generate(), fixture.actor().tenantId(), fixture.projectId(),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC), 1_000_000L);
        TenantContext.clear();

        evaluate(fixture, rule, Uuid7.generate(), 31, Instant.now());

        TenantContext.set(fixture.scope());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM alarm_notification_delivery WHERE project_id=?",
                        String.class,
                        fixture.projectId()))
                .isEqualTo("SUPPRESSED_QUOTA");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type=?",
                        Integer.class,
                        fixture.projectId(),
                        NotificationDeliveryRequest.EVENT_TYPE))
                .isZero();
        jdbcTemplate.update(
                "DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric='NOTIFICATION_DELIVERY'",
                fixture.projectId());
        TenantContext.clear();
    }

    /** 零额度显式禁用，不与正额度 HARD_LIMIT 的软限放行混淆；抑制仍保留激活与审计事实。 */
    @org.junit.jupiter.params.ParameterizedTest(name = "notification limit={0}, used={1}, status={2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "0, 0, SUPPRESSED_QUOTA",
            "10, 10, QUEUED",
            "10, 12, SUPPRESSED_QUOTA"
    })
    void notificationZeroLimitAndPositiveThresholdKeepDistinctSemantics(
            long limit, long used, String expectedStatus) throws Exception {
        Fixture fixture = fixture("notification-boundary");
        AlarmRule rule = createRule(fixture, "通知额度边界", 0, 0, 30, 25);
        UUID policyId = Uuid7.generate();
        UUID previousPolicy;
        TenantContext.set(fixture.scope());
        try {
            AlarmNotificationGroup group = notificationService.createGroup(
                    fixture.projectId(), "额度边界值班组", true);
            notificationService.createRecipient(fixture.projectId(), group.id(),
                    NotificationChannel.EMAIL, "quota-boundary@example.com", true);
            AlarmNotificationTemplate template = notificationService.createTemplate(
                    fixture.projectId(), "额度边界模板", NotificationChannel.EMAIL,
                    "告警", "当前值 ${alarm.value}", true);
            notificationService.createBinding(fixture.projectId(), rule.id(), group.id(),
                    template.id(), NotificationChannel.EMAIL, true);
            previousPolicy = jdbcTemplate.queryForObject(
                    "SELECT quota_policy_id FROM sys_tenant WHERE id=?", UUID.class,
                    fixture.actor().tenantId());
        } finally {
            TenantContext.clear();
        }
        try (var owner = fixtureOwnerConnection()) {
            var sql = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(owner, true));
            sql.update("INSERT INTO sys_quota_policy(id,code,notification_delivery_daily_limit) VALUES (?,?,?)",
                    policyId, "nq" + policyId.toString().replace("-", "").substring(0, 20), limit);
            try {
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?",
                        policyId, fixture.actor().tenantId());
                if (used > 0) {
                    sql.update("""
                            INSERT INTO sys_usage_counter_daily
                                (id,tenant_id,project_id,usage_date,metric,used_value)
                            VALUES (?,?,?,?, 'NOTIFICATION_DELIVERY', ?)
                            """, Uuid7.generate(), fixture.actor().tenantId(), fixture.projectId(),
                            java.time.LocalDate.now(java.time.ZoneOffset.UTC), used);
                }
                UUID messageId = Uuid7.generate();
                Instant received = Instant.now();
                evaluate(fixture, rule, messageId, 31, received);
                evaluate(fixture, rule, messageId, 31, received);
                AlarmInstance instance = onlyInstance(fixture);
                assertThat(instance.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
                assertThat(events(fixture, instance)).extracting(AlarmEvent::eventType)
                        .containsExactlyInAnyOrder(AlarmEvent.EventType.PENDING, AlarmEvent.EventType.ACTIVATED);
                assertThat(sql.queryForList(
                        "SELECT status FROM alarm_notification_delivery WHERE project_id=?",
                        String.class, fixture.projectId())).containsExactly(expectedStatus);
                assertThat(sql.queryForObject("""
                        SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type=?
                        """, Integer.class, fixture.projectId(), NotificationDeliveryRequest.EVENT_TYPE))
                        .isEqualTo("QUEUED".equals(expectedStatus) ? 1 : 0);
                assertThat(sql.queryForObject("""
                        SELECT count(*) FROM alarm_notification_delivery
                        WHERE project_id=? AND status='SUPPRESSED_QUOTA' AND last_outbox_event_id IS NOT NULL
                        """, Integer.class, fixture.projectId())).isZero();
            } finally {
                sql.update("DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric='NOTIFICATION_DELIVERY'",
                        fixture.projectId());
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?",
                        previousPolicy, fixture.actor().tenantId());
                sql.update("DELETE FROM sys_quota_policy WHERE id=?", policyId);
            }
        }
    }

    /** 规则名称唯一、跨项目资源隐藏、VIEWER 可读但不能 ACK/CLEAR 都经真实 HTTP/RLS 验收。 */
    @Test
    void enforcesRuleIsolationNameConflictAndViewerMaintainBoundary() throws Exception {
        Fixture owner = fixture("access-owner");
        Fixture stranger = fixture("access-stranger");
        AlarmRule rule = createRule(owner, "唯一名称", 0, 0, 30, 25);
        TenantContext.set(owner.scope());
        assertThatThrownBy(
                        () ->
                                ruleService.create(
                                        owner.projectId(),
                                        command("唯一名称", owner.deviceId(), 0, 0, 30, 25)))
                .hasFieldOrPropertyWithValue("errorCode", AlarmErrorCode.RULE_NAME_CONFLICT);
        TenantContext.clear();
        evaluate(owner, rule, Uuid7.generate(), 31, Instant.parse("2026-08-09T13:00:00Z"));
        AlarmInstance active = onlyInstance(owner);

        MvcResult hidden =
                mockMvc.perform(
                                get("/api/v1/projects/%s/alarms/%s"
                                                .formatted(owner.projectId(), active.id()))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + stranger.actor().accessToken()))
                        .andReturn();
        assertThat(code(hidden)).isEqualTo(50001);

        jdbcTemplate.update(
                "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?,"
                        + " 'VIEWER')",
                Uuid7.generate(),
                owner.projectId(),
                stranger.actor().accountId());
        Actor viewer = switchProject(stranger.actor(), owner.projectId());
        MvcResult read =
                mockMvc.perform(
                                get("/api/v1/projects/%s/alarms/%s"
                                                .formatted(owner.projectId(), active.id()))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + viewer.accessToken()))
                        .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        MvcResult forbidden =
                mockMvc.perform(
                                post("/api/v1/projects/%s/alarms/%s/ack"
                                                .formatted(owner.projectId(), active.id()))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + viewer.accessToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"version\":%d}".formatted(active.version())))
                        .andReturn();
        assertThat(code(forbidden)).isEqualTo(40005);

        TenantContext.set(owner.scope());
        AlarmInstance manual =
                instanceService.clear(owner.projectId(), active.id(), active.version());
        assertThat(manual.conditionState()).isEqualTo(AlarmInstance.ConditionState.CLEARED);
        assertThat(manual.clearReason()).isEqualTo(AlarmInstance.ClearReason.MANUAL);
        assertThat(manual.ackState()).isEqualTo(AlarmInstance.AckState.UNACKNOWLEDGED);
    }

    /** 创建固定数值规则，并确保服务调用使用与 HTTP 相同的项目/账号上下文。 */
    private AlarmRule createRule(
            Fixture fixture,
            String name,
            int triggerDuration,
            int clearDuration,
            double trigger,
            double clear) {
        TenantContext.set(fixture.scope());
        try {
            return ruleService.create(
                    fixture.projectId(),
                    command(
                            name,
                            fixture.deviceId(),
                            triggerDuration,
                            clearDuration,
                            trigger,
                            clear));
        } finally {
            TenantContext.clear();
        }
    }

    /** 构造固定白名单数值规则。 */
    private static AlarmRuleCommand command(
            String name,
            UUID deviceId,
            int triggerDuration,
            int clearDuration,
            double trigger,
            double clear) {
        return new AlarmRuleCommand(
                name,
                "HIGH_TEMPERATURE",
                deviceId,
                "temperature",
                AlarmRule.ComparisonOperator.GT,
                trigger,
                triggerDuration,
                AlarmRule.ComparisonOperator.LT,
                clear,
                clearDuration,
                AlarmRule.Severity.WARNING,
                true,
                null);
    }

    /** 使用可信评估端口模拟已完成 inbox/物模型校验的一个数值消息。 */
    private void evaluate(
            Fixture fixture, AlarmRule rule, UUID messageId, double value, Instant receivedAt) {
        TenantContext.set(fixture.scope());
        try {
            evaluationService.evaluate(
                    new AlarmEvaluationInput(
                            messageId,
                            fixture.actor().tenantId(),
                            fixture.projectId(),
                            fixture.deviceId(),
                            rule.propertyKey(),
                            value,
                            receivedAt.minusMillis(10),
                            receivedAt,
                            "s6-test"));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * @return 当前项目中最新事故
     */
    private AlarmInstance onlyInstance(Fixture fixture) {
        List<AlarmInstance> values = instances(fixture);
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    /**
     * @return 当前项目全部事故
     */
    private List<AlarmInstance> instances(Fixture fixture) {
        TenantContext.set(fixture.scope());
        try {
            return instanceService.page(fixture.projectId(), null, 100).items();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * @return 事故事件
     */
    private List<AlarmEvent> events(Fixture fixture, AlarmInstance instance) {
        TenantContext.set(fixture.scope());
        try {
            return instanceService
                    .pageEvents(fixture.projectId(), instance.id(), null, 100)
                    .items();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在真实 listener 异步推进状态时轮询数据库，不用固定长等待掩盖消费超时。 */
    private void awaitDeliveryStatus(Fixture fixture, UUID deliveryId, String expectedStatus)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            TenantContext.set(fixture.scope());
            String actual = jdbcTemplate.queryForObject(
                    "SELECT status FROM alarm_notification_delivery WHERE project_id=? AND id=?",
                    String.class,
                    fixture.projectId(),
                    deliveryId);
            TenantContext.clear();
            if (expectedStatus.equals(actual)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("通知投递未在 10 秒内进入状态: " + expectedStatus);
    }

    /**
     * 等待真实 Kafka listener 成功返回并提交指定记录之后的 offset，排除“消息尚未消费就检查数据库”的假阳性。
     *
     * @param groupId 生产 listener 固定消费组
     * @param partition 第二条消息所在分区
     * @param expectedOffset 消费完成后应提交的下一 offset
     */
    private void awaitConsumerCommitted(String groupId, TopicPartition partition, long expectedOffset)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        try (AdminClient admin = AdminClient.create(java.util.Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            while (Instant.now().isBefore(deadline)) {
                var offsets = admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata()
                        .get(2, java.util.concurrent.TimeUnit.SECONDS);
                var committed = offsets.get(partition);
                if (committed != null && committed.offset() >= expectedOffset) {
                    return;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("通知 Kafka listener 未在 10 秒内提交重复消息 offset: " + expectedOffset);
    }

    /** @return 使用真实 PostgreSQL 事实和 Redis 派生层生成的项目概要 */
    private com.things.link.telemetry.application.OverviewSnapshot overview(Fixture fixture) {
        TenantContext.set(fixture.scope());
        try {
            return overviewService.get(fixture.projectId());
        } finally {
            TenantContext.clear();
        }
    }

    /** 创建租户级 App 用户；用户名附加随机后缀，避免同 JVM 其他集成夹具冲突。 */
    private UUID createAppUser(Fixture fixture, String username, String status) {
        TenantContext.set(fixture.scope());
        UUID appUserId = Uuid7.generate();
        jdbcTemplate.update(
                """
                INSERT INTO app_user (id,tenant_id,username,password_hash,status)
                VALUES (?,?,?,?,?)
                """,
                appUserId,
                fixture.actor().tenantId(),
                username + '-' + appUserId,
                "{noop}unused-in-push-expansion-test",
                status);
        return appUserId;
    }

    /** 创建项目角色事实；DISABLED 用例必须保留记录而不是假装用户不存在。 */
    private void createAppRole(Fixture fixture, UUID appUserId, String status) {
        TenantContext.set(fixture.scope());
        jdbcTemplate.update(
                """
                INSERT INTO app_user_role (id,tenant_id,project_id,app_user_id,role,status)
                VALUES (?,?,?,?, 'OBSERVER', ?)
                """,
                Uuid7.generate(),
                fixture.actor().tenantId(),
                fixture.projectId(),
                appUserId,
                status);
    }

    /** 创建设备授权事实；CLOSED 用例保留历史关系以证明 audience 只读取 ACTIVE。 */
    private void createDeviceRelation(Fixture fixture, UUID appUserId, String status) {
        TenantContext.set(fixture.scope());
        jdbcTemplate.update(
                """
                INSERT INTO app_user_device
                    (id,tenant_id,project_id,app_user_id,device_id,relation_role,status)
                VALUES (?,?,?,?,?, 'MEMBER', ?)
                """,
                Uuid7.generate(),
                fixture.actor().tenantId(),
                fixture.projectId(),
                appUserId,
                fixture.deviceId(),
                status);
    }

    /** 创建发送前复核初始时四项均 ACTIVE 的 App 用户。 */
    private UUID createAuthorizedPushUser(Fixture fixture, String username) {
        UUID appUserId = createAppUser(fixture, username, "ACTIVE");
        createAppRole(fixture, appUserId, "ACTIVE");
        createDeviceRelation(fixture, appUserId, "ACTIVE");
        return appUserId;
    }

    /** 通过生产服务写受 AES-GCM 保护的安装事实，返回稳定 ID 且不直接伪造密文列。 */
    private UUID registerPushToken(
            Fixture fixture, UUID appUserId, UUID installationId, String plainToken) {
        TenantContext.set(fixture.scope());
        pushTokenService.register(
                fixture.actor().tenantId(),
                fixture.projectId(),
                appUserId,
                installationId,
                AppPushToken.Provider.MOCK,
                plainToken);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_push_token"
                        + " WHERE tenant_id=? AND app_user_id=? AND installation_id=?",
                UUID.class,
                fixture.actor().tenantId(),
                appUserId,
                installationId);
    }

    /** 占满 PUSH 四槽，确保竞态夹具先完成撤权再允许 worker 复核。 */
    private List<NotificationExternalGuard.Guard> occupyPushBulkhead() {
        List<NotificationExternalGuard.Guard> occupied = new java.util.ArrayList<>();
        for (int index = 0; index < 4; index++) {
            NotificationExternalGuard.Guard guard =
                    notificationExternalGuard.tryAcquire("PUSH", "PUSH");
            assertThat(guard).isNotNull();
            occupied.add(guard);
        }
        return occupied;
    }

    /** @return 当前项目某安装实例对应的唯一 PUSH 投递 ID */
    private UUID pushDeliveryId(Fixture fixture, UUID pushTokenId) {
        TenantContext.set(fixture.scope());
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM alarm_notification_delivery"
                            + " WHERE project_id=? AND channel='PUSH' AND push_token_id=?",
                    UUID.class,
                    fixture.projectId(),
                    pushTokenId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 通过真实 API 创建含可上报数值属性的已发布设备；规则服务会再次经 device application 端口校验。 */
    private Fixture fixture(String prefix) throws Exception {
        String nonce = prefix + '-' + UUID.randomUUID();
        Actor actor = registerAndLogin(nonce + "@example.com");
        UUID projectId = createProject(actor, "S6 " + prefix);
        actor = switchProject(actor, projectId);
        JsonNode type =
                body(
                        mockMvc.perform(
                                post("/api/v1/projects/%s/device-types".formatted(projectId))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + actor.accessToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"typeKey\":\"%s\",\"name\":\"S6 温度设备\",\"deviceKind\":\"DIRECT\",\"payloadProtocol\":\"STANDARD\",\"networkType\":\"WIFI\"}"
                                                        .formatted(
                                                                "t"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .replace(
                                                                                        "-",
                                                                                        "")))));
        UUID typeId = UUID.fromString(type.get("id").asString());
        body(
                mockMvc.perform(
                        post("/api/v1/projects/%s/device-types/%s/properties"
                                        .formatted(projectId, typeId))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"propertyKey\":\"temperature\",\"name\":\"温度\",\"accessType\":\"REPORT\",\"dataType\":\"NUMBER\",\"unit\":\"C\",\"minimumValue\":-40,\"maximumValue\":125,\"sortOrder\":0}")));
        body(
                mockMvc.perform(
                        post("/api/v1/projects/%s/device-types/%s/publish"
                                        .formatted(projectId, typeId))
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + actor.accessToken())));
        JsonNode device =
                body(
                        mockMvc.perform(
                                post("/api/v1/projects/%s/devices".formatted(projectId))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + actor.accessToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"deviceTypeId\":\"%s\",\"deviceKey\":\"d%s\",\"name\":\"S6 温度设备\"}"
                                                        .formatted(
                                                                typeId,
                                                                UUID.randomUUID()
                                                                        .toString()
                                                                        .replace("-", "")))));
        UUID deviceId = UUID.fromString(device.get("id").asString());
        // 版本发布属控制面（gap 2.3），数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通 versioned 摄入链。
        seedThingModelVersion(actor.tenantId(), projectId, typeId, deviceId, TEMPERATURE_SNAPSHOT);
        return new Fixture(actor, projectId, deviceId);
    }

    /** 注册、验证和登录。 */
    private Actor registerAndLogin(String email) throws Exception {
        rateLimiter.clear();
        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                        .formatted(email, PASSWORD)));
        jdbcTemplate.update(
                "UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(email, PASSWORD)))
                        .andReturn();
        String refresh =
                login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                        .filter(v -> v.startsWith("tc_refresh="))
                        .map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                        .findFirst()
                        .orElseThrow();
        UUID accountId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        UUID tenantId =
                jdbcTemplate.queryForObject(
                        "SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?",
                        UUID.class,
                        accountId);
        return new Actor(
                accountId,
                tenantId,
                JSON.readTree(login.getResponse().getContentAsString())
                        .get("accessToken")
                        .asString(),
                refresh);
    }

    /** 创建项目。 */
    private UUID createProject(Actor actor, String name) throws Exception {
        JsonNode project =
                body(
                        mockMvc.perform(
                                post("/api/v1/projects")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + actor.accessToken())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"%s\",\"region\":\"sh-1\"}"
                                                        .formatted(name))));
        return UUID.fromString(project.get("id").asString());
    }

    /** 切换 project claim。 */
    private Actor switchProject(Actor actor, UUID projectId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/switch-project")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + actor.accessToken())
                                        .cookie(new Cookie("tc_refresh", actor.refreshToken()))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                        .andReturn();
        String refresh =
                result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                        .filter(v -> v.startsWith("tc_refresh="))
                        .map(v -> v.substring("tc_refresh=".length(), v.indexOf(';')))
                        .findFirst()
                        .orElse(actor.refreshToken());
        return new Actor(
                actor.accountId(),
                actor.tenantId(),
                JSON.readTree(result.getResponse().getContentAsString())
                        .get("accessToken")
                        .asString(),
                refresh);
    }

    /** 执行 MVC 并解析 JSON，非成功响应应让夹具立即失败。 */
    private JsonNode body(org.springframework.test.web.servlet.ResultActions action)
            throws Exception {
        MvcResult result = action.andReturn();
        assertThat(result.getResponse().getStatus()).isBetween(200, 299);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** 统一错误码提取。 */
    private static int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    /** 当前测试项目与设备。 */
    private record Fixture(Actor actor, UUID projectId, UUID deviceId) {
        TenantScope scope() {
            return new TenantScope(actor.tenantId(), projectId, actor.accountId());
        }
    }

    /** 会话与租户归属。 */
    private record Actor(UUID accountId, UUID tenantId, String accessToken, String refreshToken) {}
}
