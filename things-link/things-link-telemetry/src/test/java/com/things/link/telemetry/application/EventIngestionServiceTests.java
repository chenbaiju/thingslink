package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceEventIngestionContext;
import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 事件事务编排反例；真实回滚、全局碰撞、RLS与迁移由bootstrap的独立PG测试证明。 */
class EventIngestionServiceTests {
    private final EventUplinkMessage message = message(Map.of("temperature", 21, "token", "private-value"));
    private FakeJdbc jdbc;
    private TransactionLocalRlsScope rls;
    private DeviceIngestionService devices;
    private ProjectLifecycleAccessService lifecycle;
    private ProjectDailyQuotaDecisionService quota;
    private MessageLogService logs;
    private EventIngestionService service;

    @BeforeEach
    void prepare() {
        jdbc = new FakeJdbc(); rls = mock(TransactionLocalRlsScope.class);
        devices = mock(DeviceIngestionService.class); lifecycle = mock(ProjectLifecycleAccessService.class);
        quota = mock(ProjectDailyQuotaDecisionService.class); logs = mock(MessageLogService.class);
        ObjectMapper json = new ObjectMapper();
        service = new EventIngestionService(jdbc, rls, devices, lifecycle, quota, logs,
                new MessageLogRedactor(json), json);
        when(devices.validateReportedEvent(any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(context(message));
        when(lifecycle.lockActiveForWrite(message.tenantId(), message.projectId())).thenReturn(true);
        when(quota.decisionTrustedProject(eq(message.tenantId()),eq(message.projectId()),any()))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL,false));
    }

    @Test
    void successfulReplayDoesNotRevalidateOrAddAnyFactEvenWithoutInbox() {
        jdbc.existing.add(List.of(true));
        assertThat(service.ingest(message)).isFalse();
        verify(rls).establish(message.tenantId(),message.projectId());
        verifyNoInteractions(devices,lifecycle,quota,logs);
        assertThat(jdbc.writes).isEmpty();
    }

    @Test
    void changedOriginalSemanticIsConflictBeforeCurrentModel() {
        jdbc.existing.add(List.of(false));
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException)error).errorCode().code()).isEqualTo(30059));
        verifyNoInteractions(devices,lifecycle,quota,logs);
    }

    @Test
    void invisibleGlobalInboxCollisionCannotBecomeSuccessfulReplay() {
        jdbc.acquired=0;
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.reads).hasSize(2);
        verifyNoInteractions(lifecycle,quota,logs);
        assertThat(jdbc.writes).hasSize(1);
    }

    @Test
    void concurrentWinnerUsesFreshSemanticReadAndDoesNotChargeAgain() {
        jdbc.existing.add(List.of()); jdbc.existing.add(List.of(true)); jdbc.acquired=0;
        assertThat(service.ingest(message)).isFalse();
        verifyNoInteractions(lifecycle,quota,logs);
    }

    @Test
    void schemaRunsBeforeInboxAndFailureDoesNotBecomeInfrastructureFailure() {
        RuntimeException rejected = new BusinessException(com.things.link.telemetry.domain.EventErrorCode.EVENT_INVALID);
        when(devices.validateReportedEvent(any(),any(),any(),any(),any(),any(),any())).thenThrow(rejected);
        assertThatThrownBy(() -> service.ingest(message)).isSameAs(rejected);
        assertThat(jdbc.writes).isEmpty(); verifyNoInteractions(lifecycle,quota,logs);
    }

    @ParameterizedTest
    @EnumSource(value=QuotaStatus.class,names={"HARD_LIMIT","DEGRADED"})
    void hardOrDegradedQuotaRejectsEntireFirstEvent(QuotaStatus status) {
        when(quota.decisionTrustedProject(message.tenantId(),message.projectId(),QuotaMetric.UPLINK_BYTES))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(status,false));
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException)error).errorCode().code()).isEqualTo(30071));
        assertThat(jdbc.writes).hasSize(1); verifyNoInteractions(logs);
    }

    @Test
    void disabledNormalQuotaAlsoRejectsRatherThanDegradesParameters() {
        when(quota.decisionTrustedProject(message.tenantId(),message.projectId(),QuotaMetric.UPLINK_MESSAGE))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL,true));
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.writes).hasSize(1); verifyNoInteractions(logs);
    }

    @Test
    void unavailableAuthorityRemainsRetryableAndNoEventOrLogIsWritten() {
        RuntimeException unavailable=new DataAccessResourceFailureException("unavailable");
        when(quota.decisionTrustedProject(message.tenantId(),message.projectId(),QuotaMetric.UPLINK_MESSAGE))
                .thenThrow(unavailable);
        assertThatThrownBy(() -> service.ingest(message)).isSameAs(unavailable);
        assertThat(jdbc.writes).hasSize(1); verifyNoInteractions(logs);
    }

    @Test
    void inactiveProjectStopsBeforeQuotaOrEvent() {
        when(lifecycle.lockActiveForWrite(message.tenantId(),message.projectId())).thenReturn(false);
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(ProjectIngestionRejectedException.class);
        assertThat(jdbc.writes).hasSize(1); verifyNoInteractions(quota,logs);
    }

    @Test
    void fullParametersReachSchemaAndPrivateDigestButOnlyRedactedParametersReachFactsAndLog() {
        when(quota.decisionTrustedProject(message.tenantId(),message.projectId(),QuotaMetric.UPLINK_BYTES))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.SOFT_LIMIT,false));
        assertThat(service.ingest(message)).isTrue();
        verify(devices).validateReportedEvent(message.tenantId(),message.projectId(),message.deviceId(),
                message.modelVersion(),message.receivedAt(),message.eventKey(),message.params());
        var event=jdbc.writes.get(1);
        assertThat(event.arguments()[12]).isEqualTo("{\"temperature\":21}");
        assertThat(event.arguments()[13]).isEqualTo(true);
        assertThat((String)event.arguments()[14]).contains("private-value");
        var captured=org.mockito.ArgumentCaptor.forClass(DeviceMessageLogCommand.class);
        verify(logs).log(captured.capture());
        assertThat(captured.getValue().payloadSummary()).doesNotContain("token","private-value");
        assertThat(captured.getValue().messageType()).isEqualTo("EVENT");
        assertThat(captured.getValue().rawBytes()).isEqualTo(message.rawBytes());
        verify(quota,never()).decisionTrustedProject(any(),any(),eq(QuotaMetric.TIME_SERIES_POINT));
    }

    @Test
    void emptyParametersRemainValidAndDoNotInventRedaction() {
        EventUplinkMessage empty=message(Map.of());
        when(devices.validateReportedEvent(any(),any(),any(),any(),any(),any(),any())).thenReturn(context(empty));
        when(lifecycle.lockActiveForWrite(empty.tenantId(),empty.projectId())).thenReturn(true);
        when(quota.decisionTrustedProject(eq(empty.tenantId()),eq(empty.projectId()),any()))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL,false));
        assertThat(service.ingest(empty)).isTrue();
        assertThat(jdbc.writes.get(1).arguments()[12]).isEqualTo("{}");
        assertThat(jdbc.writes.get(1).arguments()[13]).isEqualTo(false);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "0.12345678901234567890123456789012345678", "1e-308", "1.0"})
    void redactionPreservesExactDecimalsAndLargeIntegerInPersistedSqlParameters(String decimalText) {
        BigDecimal decimal = new BigDecimal(decimalText);
        BigInteger integer = new BigInteger("12345678901234567890123456789012345678");
        EventUplinkMessage precise = message(Map.of("decimal", decimal, "integer", integer,
                "token", "synthetic-private-value"));
        when(devices.validateReportedEvent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(context(precise));
        when(lifecycle.lockActiveForWrite(precise.tenantId(), precise.projectId())).thenReturn(true);
        when(quota.decisionTrustedProject(eq(precise.tenantId()), eq(precise.projectId()), any()))
                .thenReturn(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL, false));

        assertThat(service.ingest(precise)).isTrue();
        Statement event = jdbc.writes.get(1);
        assertThat(event.sql()).contains("INSERT INTO ts_device_event", "?::jsonb");
        String safe = (String) event.arguments()[12];
        ObjectMapper exact = new ObjectMapper().rebuild()
                .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        var stored = exact.readTree(safe);
        assertThat(stored.get("decimal").decimalValue()).isEqualTo(decimal);
        assertThat(stored.get("integer").bigIntegerValue()).isEqualTo(integer);
        assertThat(stored.has("token")).isFalse();
        assertThat(event.arguments()[13]).isEqualTo(true);
        var original = exact.readTree((String) event.arguments()[14]);
        assertThat(original.get("params").get("decimal").decimalValue()).isEqualTo(decimal);
        assertThat(original.get("params").get("token").asString()).isEqualTo("synthetic-private-value");
        var captured = org.mockito.ArgumentCaptor.forClass(DeviceMessageLogCommand.class);
        verify(logs).log(captured.capture());
        assertThat(captured.getValue().payloadSummary()).isEqualTo(safe);
    }

    @Test
    void logFailurePropagatesToOriginalTransaction() {
        RuntimeException failure=new DataAccessResourceFailureException("log");
        when(logs.log(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.ingest(message)).isSameAs(failure);
    }

    @Test
    void onlyExactInboxQuotaConstraintBecomesPermanentBusinessRejection() {
        jdbc.inboxFailure=postgresFailure("23514","sys_shc_local_message_usage_quota");
        assertThatThrownBy(() -> service.ingest(message)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException)error).errorCode().code()).isEqualTo(30071));
        verifyNoInteractions(lifecycle,quota,logs);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"23514,sys_shc_local_message_usage_quota_near", "23514,tenant_mismatch", "55P03,sys_shc_local_message_usage_quota", "08006,sys_shc_local_message_usage_quota"})
    void unrelatedConstraintStateOrUnavailableDatabaseKeepsOriginalRetryableFailure(String state,String constraint) {
        jdbc.inboxFailure=postgresFailure(state,constraint);
        assertThatThrownBy(() -> service.ingest(message)).isSameAs(jdbc.inboxFailure);
        verifyNoInteractions(lifecycle,quota,logs);
    }

    @Test
    void namedConstraintOutsideInboxInsertDoesNotBecomeBusinessQuota() {
        jdbc.eventFailure=postgresFailure("23514","sys_shc_local_message_usage_quota");
        assertThatThrownBy(() -> service.ingest(message)).isSameAs(jdbc.eventFailure);
        verifyNoInteractions(logs);
    }

    private static org.springframework.dao.DataAccessException postgresFailure(String state,String constraint) {
        var server=new org.postgresql.util.ServerErrorMessage("SERROR\0C"+state+"\0Mcontrolled\0n"+constraint+"\0\0");
        return new org.springframework.dao.DataIntegrityViolationException("controlled",
                new org.postgresql.util.PSQLException(server));
    }

    private static DeviceEventIngestionContext context(EventUplinkMessage message) {
        return new DeviceEventIngestionContext(message.tenantId(),UUID.randomUUID(),UUID.randomUUID(),
                message.modelVersion(),"a".repeat(64),"PG_JSONB_TEXT_V1_SHA256",message.eventKey(),"INFO",
                DeviceIngestionContext.Eligibility.HISTORY_ONLY);
    }
    private static EventUplinkMessage message(Map<String,Object> params) {
        Instant received=Instant.parse("2026-10-06T12:00:00Z");
        return new EventUplinkMessage(Uuid7.generate(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                TransportProtocol.MQTT,"sample","1.0.0",received.minusSeconds(86400),received,"event",100,params);
    }
    private record Statement(String sql,Object[] arguments) {}
    private static final class FakeJdbc extends JdbcTemplate {
        final List<Statement> writes=new ArrayList<>(),reads=new ArrayList<>();
        final ArrayDeque<List<Boolean>> existing=new ArrayDeque<>();
        int acquired=1;
        org.springframework.dao.DataAccessException inboxFailure,eventFailure;
        @Override public int update(String sql,Object... arguments) {
            if (sql.contains("sys_inbox_message") && inboxFailure!=null) throw inboxFailure;
            if (sql.contains("ts_device_event") && eventFailure!=null) throw eventFailure;
            writes.add(new Statement(sql,arguments)); return sql.contains("sys_inbox_message")?acquired:1;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... arguments) {
            reads.add(new Statement(sql,arguments));
            return (List<T>)(existing.isEmpty()?List.of():existing.removeFirst());
        }
    }
}
