package com.things.link.bootstrap.integration;

import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.alarm.application.AlarmInstanceService;
import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.PropertyIngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原三入口与真实PG事实；Kafka/HTTPS交付由2f另验，不伪造公开事件。 */
class WebhookAlarmSourceTests extends WebhookFixture {
    @MockitoBean(enforceOverride = true) private com.things.link.ingestion.application.RealtimeKafkaPublisher isolatedLegacyRealtimePublisher;
    @Autowired AlarmEvaluationService evaluation;
    @Autowired AlarmInstanceService management;
    @Autowired RuleAlarmActionService rules;
    @Autowired PropertyIngestionService ingestion;
    UUID rule; Instant first;
    @Override String modelSnapshot() { return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}"; }
    @BeforeEach void seedRule() {
        rule = Uuid7.generate(); first = Instant.now().truncatedTo(ChronoUnit.MICROS);
        owner.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES (?,?,?,'Webhook告警','TEMPERATURE',?,'value','GT',30,'LT',25,'WARNING')", rule, tenant, project, device);
    }
    @AfterEach void cleanupAlarm() {
        for (String table : List.of("alarm_event", "alarm_instance", "alarm_rule", "sys_outbox_event", "ts_property_point_internal", "ts_device_message_log", "ts_property_aggregate_backfill", "sys_message_log_inbox", "sys_inbox_message"))
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    @Test void thresholdEdgesUsePersistedEventIdentityAndKeepOriginalActivationTime() {
        UUID message = Uuid7.generate(); evaluate(message, 31.25, first);
        var triggered = sources().getFirst(); var payload = json.readTree(triggered.eventText()).path("payload");
        assertThat(triggered.event().eventType()).isEqualTo("alarm.triggered"); assertThat(triggered.event().resourceType()).isEqualTo("alarm");
        assertThat(triggered.event().resourceId()).isEqualTo(instance()); assertThat(triggered.event().deviceId()).isEqualTo(device);
        assertThat(triggered.event().eventId()).isEqualTo(owner.queryForObject("SELECT id FROM alarm_event WHERE project_id=? AND event_type='ACTIVATED'", UUID.class, project));
        assertThat(payload.path("sourceMessageId").asString()).isEqualTo(message.toString()); assertThat(payload.path("valueJson").asString()).isEqualTo("31.25");
        assertThat(payload.path("alarmId").asString()).isEqualTo(instance().toString()); assertThat(payload.path("ruleId").asString()).isEqualTo(rule.toString());
        assertThat(payload.path("activatedAt").asString()).isEqualTo(first.toString()); assertThat(payload.path("clearedAt").isNull()).isTrue();
        evaluate(Uuid7.generate(), 40, first.plusSeconds(10)); evaluate(Uuid7.generate(), 20, first.plusSeconds(1));
        assertThat(sources()).hasSize(1);
        evaluate(Uuid7.generate(), 20, first.plusSeconds(20));
        var recovered = sources().getLast(); var recovery = json.readTree(recovered.eventText()).path("payload");
        assertThat(recovered.event().eventType()).isEqualTo("alarm.recovered"); assertThat(recovery.path("clearReason").asString()).isEqualTo("AUTO_RECOVERY");
        assertThat(recovery.path("activatedAt").asString()).isEqualTo(first.toString());
        assertThat(owner.queryForObject("SELECT activated_at FROM alarm_instance WHERE id=?", java.sql.Timestamp.class, instance()).toInstant()).isEqualTo(first);
        assertThat(recovery.path("clearedAt").asString()).isEqualTo(first.plusSeconds(20).toString());
    }
    @Test void pendingCancellationProducesNoPublicRecovery() {
        owner.update("UPDATE alarm_rule SET trigger_duration_seconds=60 WHERE id=?", rule);
        evaluate(Uuid7.generate(), 40, first); evaluate(Uuid7.generate(), 20, first.plusSeconds(1));
        assertThat(state()).isEqualTo("CLEARED"); assertThat(sources()).isEmpty();
    }
    @Test void halfMicrosecondEventMatchesPersistedAlarmAndPublicSource() {
        Instant at = first.plusNanos(500);
        evaluate(Uuid7.generate(), 40, at);
        var source = sources().getFirst();
        Instant persisted = owner.queryForObject("SELECT occurred_at FROM alarm_event WHERE id=?",
                java.sql.Timestamp.class, source.event().eventId()).toInstant();
        assertThat(persisted).isEqualTo(at.plusNanos(500).truncatedTo(ChronoUnit.MICROS));
        assertThat(source.event().occurredAt()).isEqualTo(persisted);
        assertThat(json.readTree(source.eventText()).path("payload").path("activatedAt").asString())
                .isEqualTo(persisted.toString());
    }
    @Test void manualPendingClearProducesNoPublicRecovery() {
        owner.update("UPDATE alarm_rule SET trigger_duration_seconds=60 WHERE id=?", rule); evaluate(Uuid7.generate(), 40, first);
        scoped(() -> management.clear(project, instance(), version())); assertThat(state()).isEqualTo("CLEARED"); assertThat(sources()).isEmpty();
    }
    @Test void acknowledgeIsExcludedAndManualClearUsesActionTimeAndActor() {
        evaluate(Uuid7.generate(), 40, first.minusSeconds(30));
        scoped(() -> management.acknowledge(project, instance(), version())); assertThat(sources()).hasSize(1);
        scoped(() -> management.clear(project, instance(), version()));
        var source = sources().getLast(); var payload = json.readTree(source.eventText()).path("payload");
        assertThat(sources()).hasSize(2); assertThat(payload.path("actorId").asString()).isEqualTo(account.toString());
        assertThat(payload.path("ackState").asString()).isEqualTo("ACKNOWLEDGED"); assertThat(payload.path("clearReason").asString()).isEqualTo("MANUAL");
        assertThat(payload.path("valueJson").isNull()).isTrue(); assertThat(payload.path("sourceMessageId").isNull()).isTrue();
        assertThat(source.event().occurredAt()).isEqualTo(owner.queryForObject("SELECT received_at FROM alarm_event WHERE id=?", java.sql.Timestamp.class, source.event().eventId()).toInstant());
        assertThatThrownBy(() -> scoped(() -> management.clear(project, instance(), version()))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThat(sources()).hasSize(2);
    }
    @Test void ruleActionsProduceOriginalSourceOnceAndRepeatedNoOpsStaySilent() {
        var create = action(); assertThat(scoped(() -> rules.create(create)).changed()).isTrue();
        assertThat(scoped(() -> rules.create(create)).changed()).isFalse(); assertThat(sources()).hasSize(1);
        var clear = action(); assertThat(scoped(() -> rules.clear(clear)).changed()).isTrue();
        assertThat(scoped(() -> rules.clear(clear)).changed()).isFalse(); assertThat(sources()).hasSize(2);
        assertThat(json.readTree(sources().getLast().eventText()).path("payload").path("sourceMessageId").asString()).isEqualTo(clear.messageId().toString());
    }
    @Test void ruleClearingUnactivatedCandidateIsNotPublicRecovery() {
        owner.update("UPDATE alarm_rule SET trigger_duration_seconds=60 WHERE id=?", rule); evaluate(Uuid7.generate(), 40, first);
        assertThat(scoped(() -> rules.clear(action())).changed()).isTrue(); assertThat(sources()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"EVALUATION", "RULE", "MANUAL"})
    void eachProducerRollsBackWhenSourceWriteFailsThenRetries(String producer) {
        if (producer.equals("MANUAL")) evaluate(Uuid7.generate(), 40, first);
        var input = action(); UUID message = Uuid7.generate(); int previousEvents = rows("alarm_event");
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try { assertThatThrownBy(() -> produce(producer, input, message)).isInstanceOf(DataAccessException.class); }
        finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(rows("alarm_event")).isEqualTo(previousEvents); assertThat(sources()).hasSize(producer.equals("MANUAL") ? 1 : 0);
        if (producer.equals("MANUAL")) assertThat(state()).isEqualTo("ACTIVE"); else assertThat(rows("alarm_instance")).isZero();
        produce(producer, input, message); assertThat(sources()).hasSize(producer.equals("MANUAL") ? 2 : 1);
    }
    @Test void sourceFailureRollsBackRealTelemetryInboxShadowHistoryAndAlarmThenOriginalMessageRecovers() {
        String trigger = "test_alarm_source_" + rule.toString().replace("-", "");
        owner.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='PUBLIC_WEBHOOK_SOURCE' AND NEW.payload::jsonb->'event'->>'eventType'='alarm.triggered' THEN RAISE EXCEPTION 'TEST_ALARM_SOURCE_FAILURE'; END IF; RETURN NEW; END $$");
        var message = new StandardUplinkMessage(Uuid7.generate(), tenant, project, device, null, TransportProtocol.MQTT,
                StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", first, first, "alarm-source", 100, Map.of("value", 40));
        String before = owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?", String.class, device);
        try {
            owner.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON sys_outbox_event FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
            assertThatThrownBy(() -> ingestion.ingest(message)).isInstanceOf(DataAccessException.class);
        } finally { owner.execute("DROP TRIGGER IF EXISTS " + trigger + " ON sys_outbox_event"); owner.execute("DROP FUNCTION " + trigger + "()"); }
        for (String table : List.of("alarm_instance", "alarm_event", "sys_outbox_event", "sys_inbox_message", "ts_property_point_internal")) assertThat(rows(table)).as(table).isZero();
        assertThat(owner.queryForObject("SELECT reported::text FROM dev_shadow WHERE device_id=?", String.class, device)).isEqualTo(before);
        assertThat(ingestion.ingest(message)).isTrue(); assertThat(ingestion.ingest(message)).isFalse(); assertThat(sources()).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project)).isEqualTo(2);
    }
    @Test void originalProjectGenerationIsFrozenAndForgedRuleScopeCannotEmit() {
        owner.update("UPDATE sys_project SET lifecycle_generation=7 WHERE id=?", project); evaluate(Uuid7.generate(), 40, first);
        assertThat(sources().getFirst().event().projectGeneration()).isEqualTo(7);
        var wrong = new RuleAlarmActionInput(Uuid7.generate(), UUID.randomUUID(), project, rule, device, first, first, "scope");
        assertThatThrownBy(() -> scoped(() -> rules.clear(wrong))).isInstanceOf(com.things.link.shared.error.BusinessException.class);
        assertThat(sources()).hasSize(1); assertThat(state()).isEqualTo("ACTIVE");
    }
    @Test void outerTransactionRollbackRemovesDomainEventAndSource() {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { tx.execute(s -> { rls.establish(tenant, project); evaluation.evaluate(input(Uuid7.generate(), 40, first)); s.setRollbackOnly(); return true; }); }
        finally { TenantContext.clear(); }
        assertThat(sources()).isEmpty(); assertThat(rows("alarm_instance")).isZero();
    }
    @Test void archivedProjectCannotCreateAlarmOrSourceThroughRealIngestion() {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
        var message = new StandardUplinkMessage(Uuid7.generate(), tenant, project, device, null, TransportProtocol.MQTT,
                StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", first, first, "archived", 100, Map.of("value", 40));
        assertThatThrownBy(() -> ingestion.ingest(message)).isInstanceOf(RuntimeException.class);
        assertThat(rows("alarm_instance")).isZero(); assertThat(sources()).isEmpty(); assertThat(rows("sys_inbox_message")).isZero();
    }
    private void produce(String producer, RuleAlarmActionInput action, UUID message) {
        switch (producer) {
            case "EVALUATION" -> evaluate(message, 40, first);
            case "RULE" -> scoped(() -> rules.create(action));
            case "MANUAL" -> scoped(() -> management.clear(project, instance(), version()));
            default -> throw new IllegalArgumentException();
        }
    }
    private RuleAlarmActionInput action() { return new RuleAlarmActionInput(Uuid7.generate(), tenant, project, rule, device, first, first, "alarm-source"); }
    private AlarmEvaluationInput input(UUID message, double value, Instant at) { return new AlarmEvaluationInput(message, tenant, project, device, "value", value, at, at, "alarm-source"); }
    private void evaluate(UUID message, double value, Instant at) { scoped(() -> { evaluation.evaluate(input(message, value, at)); return true; }); }
    private <T> T scoped(Supplier<T> action) {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { return tx.execute(s -> { rls.establish(tenant, project); return action.get(); }); } finally { TenantContext.clear(); }
    }
    private List<PublicWebhookSource> sources() {
        return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND payload::jsonb->'event'->>'eventType' LIKE 'alarm.%' ORDER BY created_at,id", String.class, project)
                .stream().map(s -> json.readValue(s, PublicWebhookSource.class)).toList();
    }
    private UUID instance() { return owner.queryForObject("SELECT id FROM alarm_instance WHERE project_id=?", UUID.class, project); }
    private int version() { return owner.queryForObject("SELECT version FROM alarm_instance WHERE project_id=?", Integer.class, project); }
    private String state() { return owner.queryForObject("SELECT condition_state FROM alarm_instance WHERE project_id=?", String.class, project); }
}
